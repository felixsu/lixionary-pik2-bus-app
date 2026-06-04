import asyncio
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, Query, Path, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
import requests
import json
from datetime import datetime, timezone

from app.db import (
    init_db, save_route, save_stop, save_route_stop,
    get_all_routes, get_route_by_slug, get_route_stops
)
from typing import List
from app.schemas import normalize_sedayu_route, normalize_tj_route, LatLng, Route
from app.tracking import TrackingManager

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Initialize dynamic tracking manager
tracking_manager = TrackingManager()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup tasks
    logger.info("Initializing database...")
    init_db()
    
    logger.info("Syncing route and stop catalogs...")
    try:
        await sync_catalogs()
    except Exception as e:
        logger.error(f"Catalog sync failed at startup: {e}. Using existing cached data.")
        
    yield
    
    # Shutdown tasks
    logger.info("Cleaning up tracking manager upstreams...")
    await tracking_manager._stop_tracking()

app = FastAPI(
    title="Lixionary PIK2 Bus App Backend",
    description="Private bus tracker backend for ASG2 and T31",
    version="1.0.0",
    lifespan=lifespan
)

# Enable CORS for testing
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

async def sync_catalogs():
    """Sync static route and stop structures from Sedayu and TJ upstreams to SQLite."""
    # 1. Sync Agung Sedayu routes
    sedayu_routes_url = "https://asgapis.sedayu.one/shuttle/public/route/map/no-page"
    
    def fetch_sedayu():
        return requests.get(sedayu_routes_url, timeout=15).json()
        
    try:
        logger.info("Fetching Agung Sedayu routes...")
        sedayu_data = await asyncio.to_thread(fetch_sedayu)
        for route_raw in sedayu_data.get("data", []):
            route_normalized = normalize_sedayu_route(route_raw)
            # Save route
            save_route(route_normalized.model_dump())
            
            # Save stops and links
            for stop_raw in route_raw.get("ls_bus_stop", []):
                lat_long = stop_raw.get("lat_long") or {}
                stop_data = {
                    "id": stop_raw["id"],
                    "name": stop_raw["name"],
                    "lat": float(lat_long.get("latitude", 0)),
                    "lng": float(lat_long.get("longitude", 0))
                }
                save_stop(stop_data)
                
                # Transform schedule
                schedule = []
                for sched_raw in stop_raw.get("ls_schedule", []):
                    schedule.append({
                        "day": sched_raw["day_enum"],
                        "arrival_time": sched_raw["arrival_time"],
                        "seq": sched_raw["seq"],
                        "is_active": sched_raw["is_active"]
                    })
                    
                # Transform polyline
                polyline = []
                for poly_raw in stop_raw.get("ls_polyline", []):
                    polyline.append({
                        "lat": float(poly_raw["lat"]),
                        "lng": float(poly_raw["long"]) # Sedayu API uses long instead of longitude
                    })
                    
                save_route_stop(
                    route_id=route_normalized.id,
                    stop_id=stop_raw["id"],
                    seq=stop_raw["seq"],
                    polyline=polyline,
                    schedule=schedule
                )
        logger.info("Agung Sedayu catalog synced successfully.")
    except Exception as e:
        logger.error(f"Error syncing Agung Sedayu routes: {e}")

    # 2. Sync TransJakarta Routes (T31 and 1A)
    try:
        token = await tracking_manager._get_tj_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "X-App-OS": "android",
            "X-App-Version": "2.10.2",
            "X-Device-ID": tracking_manager.tj_device_id,
            "User-Agent": "okhttp/4.12.0"
        }
        
        for tj_route_id in ["T31", "1A"]:
            try:
                logger.info(f"Syncing TransJakarta {tj_route_id} metadata...")
                def fetch_tj_route(rid):
                    url = f"https://tijeapi.transjakarta.co.id/v1/route/{rid}"
                    return requests.get(url, headers=headers, timeout=15).json()
                    
                tj_data = await asyncio.to_thread(fetch_tj_route, tj_route_id)
                route_info = tj_data.get("data", {}).get("route", {})
                if route_info:
                    # Seed TJ route
                    route_color = '#' + route_info.get("route_color", "ffff6d")[-6:]
                    initial_lat = -6.14 if tj_route_id == "T31" else -6.11
                    initial_lng = 106.75 if tj_route_id == "T31" else 106.78
                    
                    tj_route = {
                        "id": tj_route_id,
                        "slug": tj_route_id,
                        "code": tj_route_id,
                        "name": route_info.get("route_long_name", f"TransJakarta {tj_route_id}"),
                        "type": "BRT",
                        "operator": "TRANSJAKARTA",
                        "is_active": True,
                        "color": route_color,
                        "initial_lat": initial_lat,
                        "initial_lng": initial_lng,
                        "initial_zoom": 12
                    }
                    save_route(tj_route)
                    
                    # Seed stops from both Inbound and Outbound
                    # Inbound
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
                            route_id=tj_route_id,
                            stop_id=stop_raw["stop_id"],
                            seq=seq,
                            polyline=polyline,
                            schedule=[]
                        )
                        
                    # Outbound (append after inbound or count offset)
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
                            route_id=tj_route_id,
                            stop_id=stop_raw["stop_id"],
                            seq=offset + seq,
                            polyline=polyline,
                            schedule=[]
                        )
                    logger.info(f"TransJakarta {tj_route_id} catalog synced successfully.")
            except Exception as e:
                logger.error(f"Error syncing TransJakarta {tj_route_id}: {e}")
    except Exception as e:
        logger.error(f"Failed to initialize TransJakarta token for catalog sync: {e}")

@app.get("/health")
def health():
    """Simple healthcheck endpoint."""
    return {"status": "ok"}

@app.get("/routes", response_model=List[Route])
def get_routes():
    """Retrieve all available routes."""
    try:
        raw_routes = get_all_routes()
        routes = []
        for r in raw_routes:
            center = None
            if r.get("initial_lat") is not None and r.get("initial_lng") is not None:
                center = LatLng(lat=r["initial_lat"], lng=r["initial_lng"])
            
            routes.append(Route(
                id=r.get("id"),
                slug=r["slug"],
                code=r["code"],
                name=r["name"],
                type=r["type"],
                operator=r["operator"],
                is_active=bool(r["is_active"]),
                color=r["color"],
                initial_map_center=center,
                initial_zoom=r.get("initial_zoom")
            ))
        return routes
    except Exception as e:
        logger.error(f"Error getting routes: {e}")
        raise HTTPException(status_code=500, detail="Database retrieval failed")

@app.get("/routes/{slug}/stops")
def get_stops(slug: str = Path(..., description="The route slug (e.g. ASG2, T31)")) -> list:
    """Retrieve stops and shapes for a specific route."""
    route = get_route_by_slug(slug)
    if not route:
        raise HTTPException(status_code=404, detail=f"Route '{slug}' not found")
        
    try:
        return get_route_stops(route["id"])
    except Exception as e:
        logger.error(f"Error getting stops for {slug}: {e}")
        raise HTTPException(status_code=500, detail="Database retrieval failed")

@app.get("/track")
async def track_buses(buses: str = Query(..., description="Comma-separated list of route slugs to track (e.g. ASG2,T31)")):
    """Server-Sent Events endpoint to stream real-time bus positions."""
    bus_list = [b.strip() for b in buses.split(",") if b.strip()]
    if not bus_list:
        raise HTTPException(status_code=400, detail="Invalid buses parameter")

    async def event_generator():
        # Register client and get communication queue
        queue = await tracking_manager.register_client(bus_list)
        try:
            while True:
                # Wait for next compiled positions update from manager
                data = await queue.get()
                
                # Format as SSE event
                # data is a list of serializable dicts from BusPosition models
                # Convert timestamps to string/ISO format for transport
                for pos in data:
                    if isinstance(pos.get("last_seen_at"), datetime):
                        pos["last_seen_at"] = pos["last_seen_at"].isoformat()
                        
                payload = json.dumps(data)
                yield f"data: {payload}\n\n"
        except asyncio.CancelledError:
            logger.info("SSE tracking client disconnected.")
        finally:
            # Ensure client is unregistered when connection closes
            await tracking_manager.unregister_client(queue, bus_list)

    return StreamingResponse(event_generator(), media_type="text/event-stream")
