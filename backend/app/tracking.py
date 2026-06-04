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
        self.active_queues: Set[asyncio.Queue] = set()
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
            self.active_queues.add(queue)
            
            # Update tracked buses list
            for bus in buses:
                self.tracked_buses.add(bus)
                
            logger.info(f"Registered new client. Active client count: {len(self.active_queues)}")
            
            # Start upstream connections if this is the first client
            if len(self.active_queues) == 1:
                await self._start_tracking()
                
            return queue

    async def unregister_client(self, queue: asyncio.Queue, buses: List[str]):
        async with self.lock:
            self.active_queues.remove(queue)
            logger.info(f"Unregistered client. Active client count: {len(self.active_queues)}")
            
            if len(self.active_queues) == 0:
                # Clear tracking list and stop connections
                self.tracked_buses.clear()
                await self._stop_tracking()
            else:
                # Recalculate which buses are still being tracked by remaining clients
                # For simplicity, we keep everything in tracked_buses while there are clients,
                # but we could also do client-specific tracking if needed.
                pass

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

    async def _poll_transjakarta_loop(self):
        headers = {
            "X-App-OS": "android",
            "X-App-Version": "2.10.2",
            "X-Device-ID": self.tj_device_id,
            "User-Agent": "okhttp/4.12.0"
        }
        
        while True:
            # Only poll if we are tracking T31 (or other TransJakarta routes)
            # TJ routes are checked dynamically in the loop
            has_tj_routes = any(r.upper().startswith("T31") or r == "1A" for r in self.tracked_buses)
            if not has_tj_routes:
                await asyncio.sleep(5)
                continue
                
            try:
                token = await self._get_tj_token()
                headers["Authorization"] = f"Bearer {token}"
                
                # Fetch positions around our key hubs along the T31 corridor
                for hub in T31_HUBS:
                    url = "https://tijeapi.transjakarta.co.id/v1/bus"
                    params = {
                        "latitude": hub["lat"],
                        "longitude": hub["lng"],
                        "radius": 5000  # 5 km radius
                    }
                    
                    def run_get():
                        return requests.get(url, params=params, headers=headers, timeout=10)
                        
                    r = await asyncio.to_thread(run_get)
                    
                    # Handle token expiry (401 Unauthorized)
                    if r.status_code == 401:
                        logger.warning("TJ Token expired, clearing cache...")
                        self.tj_token = None
                        break  # Break hub loop to refresh token next iteration
                        
                    r.raise_for_status()
                    data = r.json()
                    
                    buses = data.get("data") or []
                    async with self.lock:
                        for bus_raw in buses:
                            # Strip large stop arrays to optimize memory
                            bus_raw.pop('stops', None)
                            pos = normalize_tj_bus(bus_raw)
                            # Only keep it if it matches the tracked buses
                            if pos.route_slug in self.tracked_buses:
                                self.bus_positions[pos.vehicle_id] = pos
                                
                    # Add brief spacing between hub requests to be polite to the API
                    await asyncio.sleep(1)
                    
            except Exception as e:
                logger.error(f"Error in TransJakarta polling loop: {e}")
                
            # Poll every 10 seconds
            await asyncio.sleep(10)

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
                
                # Filter positions matching tracked buses
                current_updates = [
                    pos.model_dump()
                    for pos in self.bus_positions.values()
                    if pos.route_slug in self.tracked_buses
                ]
                
                # Push to all queues
                for q in self.active_queues:
                    await q.put(current_updates)
