# Data Schemas

The two APIs use different naming conventions and field types. This document
defines a **unified schema** that the app should normalize everything into.
The bottom of the file has the same schemas in Pydantic v2 (Python) and
TypeScript form for direct use in code.

---

## 1. Naming conventions across APIs

| Concept | TransJakarta | PIK 2 Sedayu | Unified (this doc) |
|---|---|---|---|
| Latitude | `latitude` (float) | `lat_long.latitude` or `lat` (in polyline) | `lat` (float) |
| Longitude | `longitude` (float) | `lat_long.longitude` or `long` (in polyline) | `lng` (float) |
| Route ID | `route_id` (string like `"T31"`) | `v_route_id` (slug like `"ASG2"`) | `route_slug` |
| Route internal ID | n/a | `route_id` (ULID) | `route_id` (ULID\|null) |
| Bus plate | `bus_body_no` | `v_vehicle_id` | `vehicle_id` |
| Route code shown to user | `route_code` | `code` | `route_code` |
| Bus position record id | n/a | `id` (ULID) | `position_id` (ULID\|null) |
| Last update timestamp | `timestamp` (string, ms epoch) | `last_update` (ISO 8601) | `last_seen_at` (datetime) |
| Speed | `speed` (int, km/h) | `speed` (float, km/h) | `speed_kmh` (float) |
| Bearing | `bearing` (int 0–359) | n/a | `bearing` (int\|null) |
| Next stop id | n/a | `next_bus_stop_id` | `next_stop_id` |
| ETA to next stop (s) | `estimated_time_next_stops` (often 0) | `eta_to_next_bus_stop` (float) | `eta_seconds` (int\|null) |
| Distance to next stop (m) | `estimated_distance_next_stops` (often 0) | `range_to_next_bus_stop` (float) | `distance_to_next_m` (float\|null) |
| Color (with `#`) | `route_color` (hex without `#`) | `path_color` (`0xAARRGGBB`) | `route_color` (`#RRGGBB`) |

---

## 2. Core entity schemas

```ts
// A single lat/lng point. Both APIs use WGS84.
LatLng = { lat: float, lng: float }

// A bus stop or BRT halte.
Stop = {
  id: string,                  // server-side id (ULID for Sedayu, may be
                               // string for TJ; use as opaque token)
  name: string,
  location: LatLng,
  route_slugs: string[],       // routes that stop here
  // Optional, only for Sedayu /route/map endpoint:
  seq?: int,                   // 1-indexed order along the route
  polyline?: LatLng[],         // path to arrive at this stop
  schedule?: ScheduleEntry[],  // arrival times per weekday
}

// An arrival time at a stop.
ScheduleEntry = {
  day: 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY'
       | 'FRIDAY' | 'SATURDAY' | 'SUNDAY',
  arrival_time: string,        // "HH:MM:SS" 24h, WIB
  seq: int,                    // 1-indexed time slot
  is_active: bool,
}

// A bus route.
Route = {
  id?: string,                 // ULID (Sedayu only)
  slug: string,                // "ASG2", "T31", "1A"
  code: string,                // "2", "T31", "1A" — shown to user
  name: string,                // "PIK 2 Millenials - PIK Avenue Via CBD"
  type: 'BRT' | 'JAK.LINK' | 'Royaltrans' | 'Rusun'
       | 'AUI' | 'SEDAYU' | 'UNKNOWN',
  operator: 'TRANSJAKARTA' | 'SEDAYU',
  is_active: bool,
  color: string,               // "#RRGGBB" with leading hash
  initial_map_center?: LatLng, // for "center map on this route" UX
  initial_zoom?: int,          // 1–20
  // Optional, only from Sedayu /route/map:
  stops?: Stop[],
  // Optional, from TJ /v1/route:
  price_idr?: int,
  trip_ids?: string[],
  facilities?: Facility[],
}

Facility = {
  name: string,                // machine name e.g. "accessible_for_all"
  title: string,               // localized e.g. "aksesibilitas umum"
  icon_url: string,            // light theme icon
  icon_dark_url: string,       // dark theme icon
}

// A live bus position (both APIs).
BusPosition = {
  position_id: string?,        // ULID, only for Sedayu. Resets on re-record.
  vehicle_id: string,          // physical plate, e.g. "TJ-572", "MYS-21286"
  route_slug: string,          // "ASG2", "T31", "1A"
  route_id: string?,           // ULID (Sedayu)
  plate_number: string,        // "2A", "2B", "2C", "T31", "1A"
  operator: 'TRANSJAKARTA' | 'SEDAYU',
  location: LatLng,
  speed_kmh: float,
  bearing?: int,               // 0-359, TJ only
  last_seen_at: datetime,      // UTC normalized

  // Optional, only when bus is on a Sedayu route:
  last_stop_id?: string,
  last_stop_location?: LatLng,
  next_stop_id?: string,
  next_stop_location?: LatLng,
  next_stop_seq?: int,
  eta_seconds?: int,
  distance_to_next_m?: float,

  // Optional:
  trip_id?: string,            // TJ: "9-R01" for a single vehicle's journey
  trip_headsign?: string,      // TJ: front-of-bus destination
  image_url?: string,          // GCS signed URL, 24h expiry
}
```

---

## 3. The `plate_number` ↔ `route_code` confusion, again

The unified schema keeps both `route_slug` (the operator's canonical route
identifier) and `plate_number` (the bus's physical plate prefix) because
they're genuinely different things.

When the user says "show me bus 2A", they could mean:

- the **physical 2A bus** (filter on `plate_number == "2A"`) — useful when
  you're tracking a specific vehicle
- **all buses on route 2** (filter on `route_slug == "ASG2"`) — useful
  when you just want to know what's serving that route

In code, the right filter depends on intent. Both are exposed because
users will mix them up.

---

## 4. Pydantic v2 schemas (Python)

Drop these straight into a `schemas.py`:

```python
from __future__ import annotations
from datetime import datetime
from typing import Literal, Optional
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
    id: Optional[str] = None                # ULID (Sedayu)
    slug: str                              # "ASG2", "T31", "1A"
    code: str                              # "2", "T31", "1A"
    name: str
    type: RouteType = 'UNKNOWN'
    operator: Operator
    is_active: bool = True
    color: str                             # "#RRGGBB"
    initial_map_center: Optional[LatLng] = None
    initial_zoom: Optional[int] = None
    stops: Optional[list[Stop]] = None
    price_idr: Optional[int] = None
    trip_ids: Optional[list[str]] = None
    facilities: Optional[list[Facility]] = None


class BusPosition(BaseModel):
    position_id: Optional[str] = None       # ULID, Sedayu only
    vehicle_id: str                         # physical plate
    route_slug: str                         # "ASG2", "T31", "1A"
    route_id: Optional[str] = None          # ULID, Sedayu only
    plate_number: str                       # "2A", "2B", "2C", "T31", "1A"
    operator: Operator
    location: LatLng
    speed_kmh: float
    bearing: Optional[int] = None          # 0-359
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
```

### 4.1 Normalizer functions

```python
def normalize_tj_bus(raw: dict) -> BusPosition:
    """Convert a TransJakarta /v1/bus record to a BusPosition."""
    return BusPosition(
        vehicle_id=raw['bus_body_no'],
        route_slug=raw['route_code'],
        route_id=None,
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
    """Convert a PIK 2 Sedayu Socket.IO bus_position event to a BusPosition.

    Note: the operator is determined by the route slug. Sedayu slugs follow no
    fixed pattern (some are 'ASG*', some are vendor codes like 'Higer' /
    'HIGER123'); the safest discriminator is the route's `name` field, or the
    `operator` value in the route catalog. As a fallback, 'T31' and '1A' are
    known to be TransJakarta mirrors.
    """
    ll = raw['lat_long']
    last_ll = raw.get('last_bus_stop_lat_long') or {}
    next_ll = raw.get('next_bus_stop_lat_long') or {}

    slug = raw['v_route_id']
    # Mirrored TJ routes in the Sedayu API: known list. Treat anything else
    # as a Sedayu route.
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
        bearing=None,
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
```

### 4.2 Route normalizer

```python
def normalize_sedayu_route(raw: dict) -> Route:
    """Convert a PIK 2 Sedayu route record to a Route.

    Note: as of 2026-06-03, the v_route_id slugs do NOT all start with 'ASG'.
    Examples that don't: 'Higer', 'HIGER123'. Determine the operator by
    membership in the known TJ-mirror set instead.
    """
    color = '#' + raw['path_color'].replace('0x', '')[-6:]  # strip alpha + 0x
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
                icon_dark_url=f['icon_dark'],
            )
            for f in (raw.get('facilities') or [])
        ],
    )
```

---

## 5. TypeScript schemas (for any browser / mobile app)

```typescript
// types.ts

export type LatLng = { lat: number; lng: number };

export type Weekday =
  | 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY'
  | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export type Operator = 'TRANSJAKARTA' | 'SEDAYU';

export type RouteType =
  | 'BRT' | 'JAK.LINK' | 'Royaltrans' | 'Rusun'
  | 'AUI' | 'SEDAYU' | 'UNKNOWN';

export interface ScheduleEntry {
  day: Weekday;
  arrival_time: string;       // "HH:MM:SS"
  seq: number;
  is_active: boolean;
}

export interface Stop {
  id: string;
  name: string;
  location: LatLng;
  route_slugs: string[];
  seq?: number;
  polyline?: LatLng[];
  schedule?: ScheduleEntry[];
}

export interface Facility {
  name: string;
  title: string;
  icon_url: string;
  icon_dark_url: string;
}

export interface Route {
  id?: string;                 // ULID (Sedayu)
  slug: string;                // "ASG2", "T31", "1A"
  code: string;                // "2", "T31", "1A"
  name: string;
  type: RouteType;
  operator: Operator;
  is_active: boolean;
  color: string;               // "#RRGGBB"
  initial_map_center?: LatLng;
  initial_zoom?: number;
  stops?: Stop[];
  price_idr?: number;
  trip_ids?: string[];
  facilities?: Facility[];
}

export interface BusPosition {
  position_id?: string;        // ULID, Sedayu only
  vehicle_id: string;          // physical plate
  route_slug: string;          // "ASG2", "T31", "1A"
  route_id?: string;           // ULID, Sedayu only
  plate_number: string;        // "2A", "2B", "2C", "T31", "1A"
  operator: Operator;
  location: LatLng;
  speed_kmh: number;
  bearing?: number;            // 0-359
  last_seen_at: string;        // ISO 8601

  last_stop_id?: string;
  last_stop_location?: LatLng;
  next_stop_id?: string;
  next_stop_location?: LatLng;
  next_stop_seq?: number;
  eta_seconds?: number;
  distance_to_next_m?: number;

  trip_id?: string;
  trip_headsign?: string;
  image_url?: string;
}
```

---

## 6. Database schema (if you want to persist)

If you cache data in SQLite / Postgres:

```sql
CREATE TABLE route (
    id TEXT PRIMARY KEY,            -- ULID (Sedayu) or slug (TJ)
    slug TEXT UNIQUE NOT NULL,      -- "ASG2", "T31"
    code TEXT NOT NULL,             -- "2", "T31"
    name TEXT NOT NULL,
    type TEXT NOT NULL,             -- BRT, JAK.LINK, Royaltrans, Rusun, AUI, SEDAYU
    operator TEXT NOT NULL,         -- TRANSJAKARTA, SEDAYU
    is_active BOOLEAN NOT NULL DEFAULT 1,
    color TEXT NOT NULL,            -- "#RRGGBB"
    price_idr INTEGER,
    raw_json JSON NOT NULL,         -- full original payload
    fetched_at TIMESTAMP NOT NULL
);

CREATE TABLE stop (
    id TEXT PRIMARY KEY,            -- server id
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    raw_json JSON NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);

CREATE TABLE route_stop (           -- many-to-many
    route_id TEXT NOT NULL REFERENCES route(id),
    stop_id  TEXT NOT NULL REFERENCES stop(id),
    seq INTEGER NOT NULL,
    polyline JSON,                  -- list of LatLng
    schedule JSON,                  -- list of ScheduleEntry
    PRIMARY KEY (route_id, stop_id)
);

CREATE TABLE bus_position (        -- time-series, rotate often
    vehicle_id TEXT NOT NULL,       -- physical plate, stable
    position_id TEXT,               -- ULID, only Sedayu
    route_slug TEXT NOT NULL,
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    speed_kmh REAL NOT NULL,
    bearing INTEGER,
    last_stop_id TEXT,
    next_stop_id TEXT,
    eta_seconds INTEGER,
    distance_to_next_m REAL,
    last_seen_at TIMESTAMP NOT NULL,
    raw_json JSON NOT NULL,
    PRIMARY KEY (vehicle_id, last_seen_at)
);

CREATE INDEX bus_position_route ON bus_position(route_slug, last_seen_at DESC);
CREATE INDEX bus_position_geo ON bus_position(lat, lng);
```

Rotation policy: drop `bus_position` rows older than 5 minutes; everything
else is static enough to keep for weeks.
