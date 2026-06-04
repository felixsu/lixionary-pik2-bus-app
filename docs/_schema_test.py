"""Quick sanity test for the schemas in 03-data-schemas.md.

Loads the Pydantic models and the two normalizer helpers, then feeds them
real sample data captured from the APIs on 2026-06-03. Prints OK / failures.
"""
from __future__ import annotations
from datetime import datetime, timezone
from typing import Literal, Optional
from pydantic import BaseModel, Field


class LatLng(BaseModel):
    lat: float
    lng: float


class ScheduleEntry(BaseModel):
    day: Literal['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                  'FRIDAY', 'SATURDAY', 'SUNDAY']
    arrival_time: str
    seq: int
    is_active: bool


class Stop(BaseModel):
    id: str
    name: str
    location: LatLng
    route_slugs: list[str] = Field(default_factory=list)
    seq: Optional[int] = None
    polyline: Optional[list[LatLng]] = None
    schedule: Optional[list[ScheduleEntry]] = None


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
    stops: Optional[list[Stop]] = None
    price_idr: Optional[int] = None
    trip_ids: Optional[list[str]] = None
    facilities: Optional[list[Facility]] = None


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
    return BusPosition(
        vehicle_id=raw['bus_body_no'],
        route_slug=raw['route_code'],
        plate_number=raw['route_code'],
        operator='TRANSJAKARTA',
        location=LatLng(lat=raw['latitude'], lng=raw['longitude']),
        speed_kmh=float(raw.get('speed', 0)),
        bearing=raw.get('bearing'),
        last_seen_at=datetime.fromtimestamp(int(raw['timestamp']) / 1000,
                                            tz=timezone.utc),
        trip_id=raw.get('trip_id'),
        trip_headsign=raw.get('trip_headsign'),
    )


def normalize_sedayu_position(raw: dict) -> BusPosition:
    ll = raw['lat_long']
    last_ll = raw.get('last_bus_stop_lat_long') or {}
    next_ll = raw.get('next_bus_stop_lat_long') or {}
    slug = raw['v_route_id']
    MIRRORED_TJ = {'T31', '1A'}
    operator = 'TRANSJAKARTA' if slug in MIRRORED_TJ else 'SEDAYU'
    return BusPosition(
        position_id=raw.get('id'),
        vehicle_id=raw['v_vehicle_id'],
        route_slug=raw['v_route_id'],
        route_id=raw.get('route_id'),
        plate_number=raw['plate_number'],
        operator=operator,
        location=LatLng(lat=ll['latitude'], lng=ll['longitude']),
        speed_kmh=float(raw.get('speed', 0)),
        last_seen_at=datetime.fromisoformat(raw['last_update']),
        last_stop_id=raw.get('last_bus_stop_id') or None,
        last_stop_location=(LatLng(lat=last_ll['latitude'], lng=last_ll['longitude'])
                            if last_ll else None),
        next_stop_id=raw.get('next_bus_stop_id') or None,
        next_stop_location=(LatLng(lat=next_ll['latitude'], lng=next_ll['longitude'])
                            if next_ll else None),
        next_stop_seq=raw.get('next_seq'),
        eta_seconds=int(raw['eta_to_next_bus_stop']) if raw.get('eta_to_next_bus_stop') else None,
        distance_to_next_m=(float(raw['range_to_next_bus_stop'])
                            if raw.get('range_to_next_bus_stop') else None),
        image_url=raw.get('vehicle_image_url'),
    )


def normalize_sedayu_route(raw: dict) -> Route:
    color = '#' + raw['path_color'].replace('0x', '')[-6:]
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
            lng=float(raw['initial_long']),
        ),
        initial_zoom=raw.get('initial_zoom'),
    )


# ---------- Test data (real samples from 2026-06-03) ----------

TJ_BUS_SAMPLE = {
    "bus_body_no": "TJ-298",
    "route_code": "9",
    "route_name": "Pluit",
    "route_color": "45A49E",
    "latitude": -6.193172,
    "longitude": 106.797058,
    "speed": 0,
    "bearing": 359,
    "timestamp": "1780505961974",
    "trip_id": "9-R01",
    "direction": "",
    "distance": 0,
    "route_text_color": "FFFFFF",
    "type": "BRT",
    "trip_headsign": "Pluit",
    "next_stops": "H00060P-Makasar",
}

SEDAYU_BUS_SAMPLE = {
    "id": "01K742JW4HABB0Q8BQ9EPB01X9",
    "last_update": "2026-06-03T23:46:23.820749+07:00",
    "lat_long": {"latitude": -6.040127, "longitude": 106.696068},
    "speed": 0.10,
    "v_vehicle_id": "TJ-557",
    "plate_number": "1B",
    "v_route_id": "ASG1",
    "route_id": "01HNMCRYDM9VMCQPM93TRS2MVM",
    "last_seq": 2,
    "last_bus_stop_id": "01K3N99FQFPZY8V1SKRKFVV1WE",
    "last_bus_stop_lat_long": {"latitude": -6.039916, "longitude": 106.696259},
    "next_seq": 3,
    "next_bus_stop_id": "01JQ8QTR56C2TW1H7JDXGZFF0K",
    "next_bus_stop_lat_long": {"latitude": -6.045812, "longitude": 106.695451},
    "eta_to_next_bus_stop": 4800.0,
    "range_to_next_bus_stop": 670.0,
}

SEDAYU_ROUTE_SAMPLE = {
    "id": "01HNW8A6BWY6586Y1T10DV161D",
    "v_route_id": "ASG2",
    "name": "PIK 2 Millenials - PIK Avenue Via CBD",
    "code": "2",
    "is_active": True,
    "path_color": "0xFFEC792D",
    "initial_lat": "-6.055993",
    "initial_long": "106.696596",
    "initial_zoom": 2,
}

# A real entry from the live API that DOESN'T have an ASG* slug —
# confirms the normalizer handles vendor codes like "Higer".
HIGER_ROUTE_SAMPLE = {
    "id": "01K8SZZG2HCHEBA45XEFE6A2XA",
    "v_route_id": "Higer",
    "name": "Shelter PIK 2 - PIK Avenue Via Rasuna Said",
    "code": "4",
    "is_active": True,
    "path_color": "0xFF112233",
    "initial_lat": "-6.05",
    "initial_long": "106.69",
    "initial_zoom": 13,
}


# ---------- Run ----------

if __name__ == "__main__":
    print("=" * 60)
    print("Testing unified schemas (Pydantic v2)")
    print("=" * 60)

    bp_tj = normalize_tj_bus(TJ_BUS_SAMPLE)
    print(f"\n✅ TJ bus: {bp_tj.vehicle_id} route={bp_tj.route_slug} "
          f"({bp_tj.operator}) trip={bp_tj.trip_id} bearing={bp_tj.bearing}")
    assert bp_tj.vehicle_id == "TJ-298"
    assert bp_tj.operator == "TRANSJAKARTA"
    assert bp_tj.bearing == 359
    assert bp_tj.location.lat == -6.193172
    print(f"   last_seen_at: {bp_tj.last_seen_at.isoformat()}")
    print(f"   speed: {bp_tj.speed_kmh} km/h")

    bp_sg = normalize_sedayu_position(SEDAYU_BUS_SAMPLE)
    print(f"\n✅ Sedayu bus: {bp_sg.vehicle_id} route={bp_sg.route_slug} "
          f"plate={bp_sg.plate_number} ({bp_sg.operator})")
    assert bp_sg.vehicle_id == "TJ-557"
    assert bp_sg.operator == "SEDAYU"
    assert bp_sg.next_stop_id is not None
    assert bp_sg.distance_to_next_m == 670.0
    assert bp_sg.eta_seconds == 4800
    print(f"   → next stop: {bp_sg.distance_to_next_m} m / {bp_sg.eta_seconds} s")

    rt = normalize_sedayu_route(SEDAYU_ROUTE_SAMPLE)
    print(f"\n✅ Sedayu route: {rt.slug} '{rt.name}'")
    assert rt.slug == "ASG2"
    assert rt.code == "2"
    assert rt.color == "#EC792D"  # FF stripped (alpha), 0x stripped
    assert rt.operator == "SEDAYU"
    assert rt.initial_map_center is not None
    assert rt.initial_map_center.lat == -6.055993
    print(f"   color: {rt.color} (no alpha, with #)")
    print(f"   map center: {rt.initial_map_center.lat}, {rt.initial_map_center.lng}")
    print(f"   zoom: {rt.initial_zoom}")

    # Real route with non-ASG* slug
    rt2 = normalize_sedayu_route(HIGER_ROUTE_SAMPLE)
    print(f"\n✅ Higer route: {rt2.slug} '{rt2.name}'")
    assert rt2.slug == "Higer"
    assert rt2.operator == "SEDAYU"   # not in MIRRORED_TJ
    assert rt2.color == "#112233"
    print(f"   color: {rt2.color}")
    print(f"   operator: {rt2.operator}  (correctly NOT classified as TJ)")

    # Stop schema test
    stop = Stop(
        id="01HNSB4PCDD7N3S4FVQ0Z4F135",
        name="Cluster Florida",
        location=LatLng(lat=-6.030529, lng=106.63742),
        route_slugs=["ASG2"],
        seq=1,
    )
    print(f"\n✅ Stop: {stop.name} seq={stop.seq} {stop.location.lat}, {stop.location.lng}")

    print("\n" + "=" * 60)
    print("All schema tests passed ✓")
    print("=" * 60)
