import asyncio
import logging
import time
import requests
import socketio
from datetime import datetime, timezone
from typing import Dict, Set, List, Optional
from app.schemas import BusPosition, normalize_tj_bus, normalize_sedayu_position

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Key transit coordinates for TransJakarta polling (covers T31 and 1A corridors)
T31_HUBS = [
    {"lat": -6.243525, "lng": 106.801878, "name": "Blok M"},
    {"lat": -6.202101, "lng": 106.799893, "name": "Petamburan"},
    {"lat": -6.107998, "lng": 106.739420, "name": "Tzu Chi PIK"},
    {"lat": -6.039902, "lng": 106.696259, "name": "Shelter PIK 2"},
    {"lat": -6.175000, "lng": 106.820000, "name": "Monas / Central Jakarta"}
]

class TrackingManager:
    def __init__(self):
        self.active_queues: Dict[asyncio.Queue, Set[str]] = {} # queue -> set of route slugs
        self.tracked_buses: Set[str] = set()
        self.bus_positions: Dict[str, BusPosition] = {}  # vehicle_id -> BusPosition
        self.lock = asyncio.Lock()
        
        # Upstream clients
        self.sio: Optional[socketio.AsyncClient] = None
        self.sio_task: Optional[asyncio.Task] = None
        self.tj_task: Optional[asyncio.Task] = None
        self.publisher_task: Optional[asyncio.Task] = None
        
        # TransJakarta Auth Caching
        self.tj_token: Optional[str] = None
        self.tj_device_id = "tj31-lixsu-felix"
        
    async def register_client(self, buses: List[str]) -> asyncio.Queue:
        async with self.lock:
            queue = asyncio.Queue()
            buses_set = set(buses)
            self.active_queues[queue] = buses_set
            
            # Update tracked buses list
            self.tracked_buses.update(buses_set)
                
            logger.info(f"Registered new client. Active client count: {len(self.active_queues)}")
            
            # Start upstream connections if this is the first client
            if len(self.active_queues) == 1:
                await self._start_tracking()
                
            return queue

    async def unregister_client(self, queue: asyncio.Queue, buses: List[str]):
        async with self.lock:
            if queue in self.active_queues:
                del self.active_queues[queue]
            logger.info(f"Unregistered client. Active client count: {len(self.active_queues)}")
            
            if len(self.active_queues) == 0:
                # Clear tracking list and stop connections
                self.tracked_buses.clear()
                await self._stop_tracking()
            else:
                # Recalculate which buses are still being tracked by remaining clients
                self.tracked_buses = set().union(*self.active_queues.values())

    async def _start_tracking(self):
        logger.info("Starting upstream connections...")
        
        # 1. Start Socket.IO client for Agung Sedayu (and mirrored TJ)
        self.sio = socketio.AsyncClient(reconnection=True, reconnection_delay=5)
        
        @self.sio.on('bus_position')
        async def on_bus_position(data):
            try:
                # Normalize and update
                pos = normalize_sedayu_position(data)
                async with self.lock:
                    self.bus_positions[pos.vehicle_id] = pos
            except Exception as e:
                logger.error(f"Error handling Sedayu bus position event: {e}")
                
        @self.sio.on('bus_position_remove')
        async def on_bus_position_remove(data):
            try:
                # data is typically the record ULID ID or a dict
                record_id = data.get('id') if isinstance(data, dict) else data
                if record_id:
                    async with self.lock:
                        # Find and delete
                        to_remove = [vid for vid, pos in self.bus_positions.items() if pos.position_id == record_id]
                        for vid in to_remove:
                            del self.bus_positions[vid]
            except Exception as e:
                logger.error(f"Error handling Sedayu bus position remove: {e}")
                
        async def connect_sio():
            url = "https://asgapis.sedayu.one"
            socketio_path = "/shuttle/busposition/v1/socket.io"
            while self.sio:
                try:
                    logger.info("Connecting to Agung Sedayu Socket.IO...")
                    await self.sio.connect(
                        url,
                        socketio_path=socketio_path,
                        transports=['websocket']
                    )
                    await self.sio.wait()
                except Exception as e:
                    logger.error(f"Agung Sedayu Socket.IO error: {e}. Retrying in 10s...")
                    await asyncio.sleep(10)
        
        self.sio_task = asyncio.create_task(connect_sio())
        
        # 2. Start TransJakarta REST polling loop (if tracking T31)
        # TODO(security): The TransJakarta API uses Guest JWT for auth. 
        # In a production app with users, we should use formal user accounts, 
        # but here we use a guest login as it's a private app run on a VPN.
        self.tj_task = asyncio.create_task(self._poll_transjakarta_loop())
        
        # 3. Start publisher task
        self.publisher_task = asyncio.create_task(self._publish_loop())

    async def _stop_tracking(self):
        logger.info("Stopping upstream connections...")
        
        # Stop Socket.IO
        if self.sio:
            try:
                await self.sio.disconnect()
            except Exception:
                pass
            self.sio = None
            
        if self.sio_task:
            self.sio_task.cancel()
            self.sio_task = None
            
        # Stop TransJakarta polling
        if self.tj_task:
            self.tj_task.cancel()
            self.tj_task = None
            
        # Stop Publisher
        if self.publisher_task:
            self.publisher_task.cancel()
            self.publisher_task = None
            
        # Clear positions
        self.bus_positions.clear()

    async def _get_tj_token(self) -> str:
        # Check cache
        if self.tj_token:
            return self.tj_token
            
        # Fetch new guest token
        url = "https://tijeapi.transjakarta.co.id/v1/auth/login/guest"
        headers = {
            "Content-Type": "application/json",
            "X-App-OS": "android",
            "X-App-Version": "2.10.2",
            "X-Device-ID": self.tj_device_id,
            "User-Agent": "okhttp/4.12.0"
        }
        payload = {"device_id": self.tj_device_id}
        
        def run_post():
            return requests.post(url, json=payload, headers=headers, timeout=10)
            
        try:
            r = await asyncio.to_thread(run_post)
            r.raise_for_status()
            res = r.json()
            # Cache the token (actual response puts it at the root)
            self.tj_token = res["token"]
            logger.info("Successfully refreshed TransJakarta guest token.")
            return self.tj_token
        except Exception as e:
            logger.error(f"Failed to fetch TransJakarta guest token: {e}")
            raise e

    async def ensure_tj_route_stops(self, route_slug: str):
        """Ensure stops for a TransJakarta route are cached in the database.
        If they don't exist, fetch them dynamically from the upstream API.
        """
        from app.db import get_route_by_slug, get_route_stops, save_stop, save_route_stop
        
        route = get_route_by_slug(route_slug)
        if not route:
            logger.warning(f"ensure_tj_route_stops: Route '{route_slug}' not found in database.")
            return
            
        if route["operator"] != "TRANSJAKARTA":
            return
            
        existing_stops = get_route_stops(route["id"])
        if existing_stops:
            return
            
        logger.info(f"ensure_tj_route_stops: Stops not cached for TransJakarta route '{route_slug}'. Fetching from API...")
        try:
            token = await self._get_tj_token()
            headers = {
                "Authorization": f"Bearer {token}",
                "X-App-OS": "android",
                "X-App-Version": "2.10.2",
                "X-Device-ID": self.tj_device_id,
                "User-Agent": "okhttp/4.12.0"
            }
            
            def fetch_tj_route():
                url = f"https://tijeapi.transjakarta.co.id/v1/route/{route_slug}"
                return requests.get(url, headers=headers, timeout=15).json()
                
            tj_data = await asyncio.to_thread(fetch_tj_route)
            
            # Seed inbound stops
            inbound_stops = tj_data.get("data", {}).get("inbound", {}).get("stops", [])
            for seq, stop_raw in enumerate(inbound_stops, 1):
                stop_data = {
                    "id": stop_raw["stop_id"],
                    "name": stop_raw["stop_name"],
                    "lat": float(stop_raw["stop_lat"]),
                    "lng": float(stop_raw["stop_lon"])
                }
                save_stop(stop_data)
                polyline = [{"lat": stop_data["lat"], "lng": stop_data["lng"]}]
                save_route_stop(
                    route_id=route["id"],
                    stop_id=stop_raw["stop_id"],
                    seq=seq,
                    polyline=polyline,
                    schedule=[]
                )
                
            # Seed outbound stops
            outbound_stops = tj_data.get("data", {}).get("outbound", {}).get("stops", [])
            offset = len(inbound_stops)
            for seq, stop_raw in enumerate(outbound_stops, 1):
                stop_data = {
                    "id": stop_raw["stop_id"],
                    "name": stop_raw["stop_name"],
                    "lat": float(stop_raw["stop_lat"]),
                    "lng": float(stop_raw["stop_lon"])
                }
                save_stop(stop_data)
                polyline = [{"lat": stop_data["lat"], "lng": stop_data["lng"]}]
                save_route_stop(
                    route_id=route["id"],
                    stop_id=stop_raw["stop_id"],
                    seq=offset + seq,
                    polyline=polyline,
                    schedule=[]
                )
            logger.info(f"ensure_tj_route_stops: Successfully cached {offset + len(outbound_stops)} stops for route '{route_slug}'.")
        except Exception as e:
            logger.error(f"ensure_tj_route_stops: Failed to fetch/cache stops for TransJakarta route '{route_slug}': {e}")

    async def _poll_transjakarta_loop(self):
        headers = {
            "X-App-OS": "android",
            "X-App-Version": "2.10.2",
            "X-Device-ID": self.tj_device_id,
            "User-Agent": "okhttp/4.12.0"
        }
        
        while True:
            from app.db import get_route_by_slug, get_route_stops
            
            # 1. Identify active TJ routes being tracked
            tj_tracked = []
            for slug in list(self.tracked_buses):
                route = get_route_by_slug(slug)
                if route and route.get("operator") == "TRANSJAKARTA":
                    tj_tracked.append(route)
                    
            if not tj_tracked:
                await asyncio.sleep(5)
                continue
                
            try:
                # 2. Extract dynamic polling hubs from stops of the active routes
                hubs = []
                for route in tj_tracked:
                    # Make sure stops are cached
                    await self.ensure_tj_route_stops(route["slug"])
                    
                    # Fetch stops from DB
                    stops = get_route_stops(route["id"])
                    if not stops:
                        continue
                        
                    # Sample 3-4 stops evenly
                    L = len(stops)
                    sampled_stops = []
                    if L <= 4:
                        sampled_stops = stops
                    else:
                        sampled_stops = [
                            stops[0],
                            stops[L // 3],
                            stops[2 * L // 3],
                            stops[L - 1]
                        ]
                        
                    for s in sampled_stops:
                        loc = s.get("location")
                        if loc:
                            hubs.append((round(loc["lat"], 4), round(loc["lng"], 4)))
                            
                # Deduplicate hubs
                unique_hubs = list(set(hubs))
                
                if not unique_hubs:
                    await asyncio.sleep(5)
                    continue
                    
                # 3. Poll /v1/bus around each unique dynamic hub
                token = await self._get_tj_token()
                headers["Authorization"] = f"Bearer {token}"
                
                logger.info(f"Polling TJ API around {len(unique_hubs)} dynamic hubs for tracked routes {[r['slug'] for r in tj_tracked]}")
                
                for lat, lng in unique_hubs:
                    url = "https://tijeapi.transjakarta.co.id/v1/bus"
                    params = {
                        "latitude": lat,
                        "longitude": lng,
                        "radius": 5000  # 5 km radius
                    }
                    
                    def run_get():
                        return requests.get(url, params=params, headers=headers, timeout=10)
                        
                    r = await asyncio.to_thread(run_get)
                    
                    # Handle token expiry (401 Unauthorized)
                    if r.status_code == 401:
                        logger.warning("TJ Token expired, clearing cache...")
                        self.tj_token = None
                        break
                        
                    r.raise_for_status()
                    data = r.json()
                    
                    buses = data.get("data") or []
                    async with self.lock:
                        for bus_raw in buses:
                            bus_raw.pop('stops', None)
                            pos = normalize_tj_bus(bus_raw)
                            # Only keep if the route is tracked
                            if pos.route_slug in self.tracked_buses:
                                self.bus_positions[pos.vehicle_id] = pos
                                
                    # Spacing between API calls to be polite
                    await asyncio.sleep(1)
                    
            except Exception as e:
                logger.error(f"Error in TransJakarta polling loop: {e}")
                
            # Poll every 15 seconds
            await asyncio.sleep(15)

    async def _publish_loop(self):
        """Periodically broadcast the current tracked bus positions to SSE clients."""
        while True:
            await asyncio.sleep(2)  # Publish every 2 seconds
            
            async with self.lock:
                if not self.active_queues:
                    continue
                    
                # Clean up old positions (stale if not seen in 5 minutes)
                now = datetime.now(timezone.utc)
                stale_keys = []
                for vid, pos in self.bus_positions.items():
                    # Parse last_seen_at as timezone aware
                    seen_time = pos.last_seen_at
                    if seen_time.tzinfo is None:
                        seen_time = seen_time.replace(tzinfo=timezone.utc)
                    if (now - seen_time).total_seconds() > 300:
                        stale_keys.append(vid)
                        
                for k in stale_keys:
                    logger.info(f"Removing stale bus: {k}")
                    del self.bus_positions[k]
                
                # Push to all queues, filtering by what each queue requested
                for q, requested_buses in list(self.active_queues.items()):
                    client_updates = [
                        pos.model_dump()
                        for pos in self.bus_positions.values()
                        if pos.route_slug in requested_buses
                    ]
                    await q.put(client_updates)
