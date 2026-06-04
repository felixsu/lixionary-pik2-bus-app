from __future__ import annotations
from datetime import datetime, timezone
from typing import Literal, Optional, List, Dict, Any
from pydantic import BaseModel, Field

class LatLng(BaseModel):
    lat: float
    lng: float

class ScheduleEntry(BaseModel):
    day: Literal['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                  'FRIDAY', 'SATURDAY', 'SUNDAY']
    arrival_time: str  # "HH:MM:SS"
    seq: int
    is_active: bool

class Stop(BaseModel):
    id: str
    name: str
    location: LatLng
    route_slugs: List[str] = Field(default_factory=list)
    seq: Optional[int] = None
    polyline: Optional[List[LatLng]] = None
    schedule: Optional[List[ScheduleEntry]] = None

class Facility(BaseModel):
    name: str
    title: str
    icon_url: str
    icon_dark_url: str

RouteType = Literal['BRT', 'JAK.LINK', 'Royaltrans', 'Rusun',
                    'AUI', 'SEDAYU', 'UNKNOWN']
Operator = Literal['TRANSJAKARTA', 'SEDAYU']

class Route(BaseModel):
    id: Optional[str] = None
    slug: str
    code: str
    name: str
    type: RouteType = 'UNKNOWN'
    operator: Operator
    is_active: bool = True
    color: str
    initial_map_center: Optional[LatLng] = None
    initial_zoom: Optional[int] = None
    stops: Optional[List[Stop]] = None
    price_idr: Optional[int] = None
    trip_ids: Optional[List[str]] = None
    facilities: Optional[List[Facility]] = None

class BusPosition(BaseModel):
    position_id: Optional[str] = None
    vehicle_id: str
    route_slug: str
    route_id: Optional[str] = None
    plate_number: str
    operator: Operator
    location: LatLng
    speed_kmh: float
    bearing: Optional[int] = None
    last_seen_at: datetime
    last_stop_id: Optional[str] = None
    last_stop_location: Optional[LatLng] = None
    next_stop_id: Optional[str] = None
    next_stop_location: Optional[LatLng] = None
    next_stop_seq: Optional[int] = None
    eta_seconds: Optional[int] = None
    distance_to_next_m: Optional[float] = None
    trip_id: Optional[str] = None
    trip_headsign: Optional[str] = None
    image_url: Optional[str] = None

# ---------- Normalizers ----------

def normalize_tj_bus(raw: dict) -> BusPosition:
    """Normalize TransJakarta bus positions to standard schema."""
    # Convert string epoch timestamp to datetime object
    try:
        ts = int(raw['timestamp']) / 1000
        dt = datetime.fromtimestamp(ts, tz=timezone.utc)
    except Exception:
        dt = datetime.now(timezone.utc)

    return BusPosition(
        vehicle_id=raw['bus_body_no'],
        route_slug=raw['route_code'],
        plate_number=raw['route_code'],
        operator='TRANSJAKARTA',
        location=LatLng(lat=raw['latitude'], lng=raw['longitude']),
        speed_kmh=float(raw.get('speed', 0)),
        bearing=raw.get('bearing'),
        last_seen_at=dt,
        trip_id=raw.get('trip_id'),
        trip_headsign=raw.get('trip_headsign'),
    )

def normalize_sedayu_position(raw: dict) -> BusPosition:
    """Normalize Agung Sedayu Socket.IO bus position event."""
    ll = raw['lat_long']
    last_ll = raw.get('last_bus_stop_lat_long') or {}
    next_ll = raw.get('next_bus_stop_lat_long') or {}
    
    slug = raw['v_route_id']
    # Check if the route is a mirrored TransJakarta route (T31, 1A)
    MIRRORED_TJ = {'T31', '1A'}
    operator = 'TRANSJAKARTA' if slug in MIRRORED_TJ else 'SEDAYU'
    
    # Parse last_update timestamp
    try:
        dt = datetime.fromisoformat(raw['last_update'])
    except Exception:
        dt = datetime.now(timezone.utc)

    return BusPosition(
        position_id=raw.get('id'),
        vehicle_id=raw['v_vehicle_id'],
        route_slug=raw['v_route_id'],
        route_id=raw.get('route_id'),
        plate_number=raw['plate_number'],
        operator=operator,
        location=LatLng(lat=ll['latitude'], lng=ll['longitude']),
        speed_kmh=float(raw.get('speed', 0)),
        last_seen_at=dt,
        last_stop_id=raw.get('last_bus_stop_id') or None,
        last_stop_location=(LatLng(lat=last_ll['latitude'], lng=last_ll['longitude'])
                            if last_ll.get('latitude') is not None else None),
        next_stop_id=raw.get('next_bus_stop_id') or None,
        next_stop_location=(LatLng(lat=next_ll['latitude'], lng=next_ll['longitude'])
                            if next_ll.get('latitude') is not None else None),
        next_stop_seq=raw.get('next_seq'),
        eta_seconds=int(raw['eta_to_next_bus_stop']) if raw.get('eta_to_next_bus_stop') else None,
        distance_to_next_m=(float(raw['range_to_next_bus_stop'])
                            if raw.get('range_to_next_bus_stop') else None),
        image_url=raw.get('vehicle_image_url'),
    )

def normalize_sedayu_route(raw: dict) -> Route:
    """Normalize Agung Sedayu route metadata to unified Route object."""
    # Convert '0xFFEC792D' to '#EC792D'
    raw_color = raw.get('path_color', '0xFF888888')
    color = '#' + raw_color.replace('0x', '')[-6:]
    
    MIRRORED_TJ = {'T31', '1A'}
    operator = 'TRANSJAKARTA' if raw['v_route_id'] in MIRRORED_TJ else 'SEDAYU'
    
    return Route(
        id=raw.get('id'),
        slug=raw['v_route_id'],
        code=str(raw['code']),
        name=raw['name'],
        type='SEDAYU' if operator == 'SEDAYU' else 'BRT',
        operator=operator,
        is_active=raw.get('is_active', True),
        color=color,
        initial_map_center=LatLng(
            lat=float(raw['initial_lat']),
            lng=float(raw['initial_long'])
        ),
        initial_zoom=raw.get('initial_zoom')
    )

def normalize_tj_route(raw: dict) -> Route:
    """Convert a TransJakarta /v1/route record to a Route."""
    desc = (raw.get('route_desc') or '').lower()
    if 'royal' in desc:
        type_ = 'Royaltrans'
    elif 'rusun' in desc:
        type_ = 'Rusun'
    elif 'jak' in desc or 'integrasi' in desc:
        type_ = 'JAK.LINK'
    else:
        type_ = 'BRT'

    return Route(
        slug=raw['route_short_name'],
        code=raw['route_short_name'],
        name=raw['route_long_name'],
        type=type_,
        operator='TRANSJAKARTA',
        is_active=True,
        color='#' + raw.get('route_color', '888888'),
        price_idr=raw.get('price'),
        trip_ids=raw.get('trip_ids') or [],
        facilities=[
            Facility(
                name=f['name'],
                title=f['title'],
                icon_url=f['icon'],
                icon_dark_url=f['icon_dark']
            )
            for f in (raw.get('facilities') or [])
        ]
    )
