# PIK 2 Sedayu Shuttle API

Reverse-engineered from **Sedayu One City v5.1.1** (Android app, package
`id.qluster.soc`). The "shuttle" tab in the app is a WebView loading
`https://shuttle.sedayu.one?source=sedayu-one-app` — a Flutter Web build
that uses the same REST + Socket.IO endpoints documented here.

This API covers the **internal Agung Sedayu shuttles** that residents of
PIK 2 (California, Florida, Tokyo Riverside, etc.) use to commute to PIK
Avenue and other parts of the township.

---

## 1. Base URL and global headers

```
Base URL:    https://asgapis.sedayu.one
WebSocket:   wss://asgapis.sedayu.one/shuttle/busposition/v1/socket.io
             (Socket.IO v4 protocol, EIO=4, transport=websocket)
```

**No authentication** is required for any endpoint. The server is happy to
serve any client. Don't abuse this — keep your polling under 1 Hz and
don't share the live position stream publicly.

Static data and live data use **different transports**:

| Data | Transport | Path |
|---|---|---|
| All routes (lightweight) | REST `GET` | `/shuttle/public/route/no-page` |
| Single route + stops + polylines + schedule (heavy) | REST `GET` | `/shuttle/public/route/map/no-page` |
| All bus stops | REST `GET` | `/shuttle/public/bus-stop/map/no-page` |
| **Live bus positions** | Socket.IO v4 WebSocket | `/shuttle/busposition/v1/socket.io` |

All REST responses share this wrapper:

```json
{ "message": "Data got correctly", "meta": {}, "data": [ ... ] }
```

---

## 2. The route catalog

The route list is stable — there are only ~11 routes. Refresh it once on
app startup, then cache it. Sample response, normalized (the full response
has signed GCS URLs that expire in 24 h — strip them for documentation):

```json
{
  "message": "Data got correctly",
  "meta": {},
  "data": [
    {
      "id": "01HNW8A6BWY6586Y1T10DV161D",
      "created_at": "2024-02-05T09:06:45.244891Z",
      "updated_at": "2026-01-21T11:15:20.375384Z",
      "v_route_id": "ASG2",
      "name": "PIK 2 Millenials - PIK Avenue Via CBD",
      "code": "2",
      "is_active": true,
      "path_color": "0xFFEC792D",
      "initial_lat": "-6.055993",
      "initial_long": "106.696596",
      "initial_zoom": 2,
      "image": "shuttle/vehicle/01HNW8A6BWY6586Y1T10DV161D.png",
      "image_url": "https://storage.googleapis.com/sedayuone_production/shuttle/vehicle/...<signed>",
      "schedule_image": {
        "id": "01K85WY0F0XMS7MC1VDK7BPY6X",
        "image": "shuttle/schedule-image/01K85WY0F0XMS7MC1VDK7BPY6X.jpeg",
        "is_active": true,
        "route_id": "01HNW8A6BWY6586Y1T10DV161D",
        "route_name": "PIK 2 Millenials - PIK Avenue Via CBD"
      }
    }
  ]
}
```

### 2.1 The complete Sedayu route catalog (as of 2026-06-03)

> ⚠️ **The v_route_id slugs change occasionally** (they're opaque server-side
> identifiers, not stable human codes). Always fetch the live list from
> `/public/route/no-page` on app startup rather than hard-coding these.

| code | v_route_id | name |
|---|---|---|
| 1 | `ASG1` | Shelter PIK 2 - PIK Avenue Via NICE |
| **2** | **`ASG2`** | **PIK 2 Millenials - PIK Avenue Via CBD** |
| 3 | `ASG3` | Tokyo Riverside Apt. - PIK Avenue Via IDD |
| 4 | `Higer` | Shelter PIK 2 - PIK Avenue Via Rasuna Said |
| W1 | `ASG5X` | Wisata PIK 2 |
| W2 | `ASG5` | Tokyo Riverside - PIK Avenue |
| W3 | `ASG5X` | Pantai Maju - GKT PIK2 |
| HC1 | `ASG4` | PIK 2 - Juanda |
| PIK2-SCK | `HIGER123` | PIK 2 - Sedayu City Kelapa Gading |
| T31 | `T31` | PIK2 - Blok M (TransJakarta BRT, mirrored into the Sedayu API) |
| 1A | `1A` | Pantai Maju - Balai Kota (TransJakarta BRT, mirrored) |

> The T31 and 1A entries are **re-published into the Sedayu API for app
> convenience**, but their bus positions come from TransJakarta, not from
> Sedayu's own fleet. If you aggregate both APIs, **deduplicate by plate
> number** so you don't show the same T31 bus twice.

### 2.2 Field reference for `Route`

| Field | Type | Notes |
|---|---|---|
| `id` | string ULID | Server-side record ID. Use this as the foreign key everywhere. |
| `v_route_id` | string | **The human-readable route slug**, e.g. `"ASG2"`. Use this in URLs and logs. |
| `code` | string | Short code, e.g. `"2"`, `"T31"`. **The bus plate prefix is `code + "A"`, `code + "B"`, …** |
| `name` | string | Human destination. |
| `is_active` | bool | `true` if route is currently operating. |
| `path_color` | string | Hex color **with `0x` prefix** (Flutter convention). Strip the `0x` and use the remaining 8 chars. The first 2 are alpha (`FF` = opaque). |
| `initial_lat` | string\|float | Map's initial center latitude. **Sometimes a string, sometimes a float.** Normalize client-side. |
| `initial_long` | string\|float | Map's initial center longitude. Same caveat. |
| `initial_zoom` | int | Map's initial zoom level (1 = world, 20 = building). |
| `image` | string | GCS path to the route's hero image. |
| `image_url` | string | Signed GCS URL, 24 h expiry. |
| `schedule_image` | object | The posted schedule as an image. **The schedule data is also in the `map` endpoint as proper records.** |
| `schedule_image.id` | string ULID | — |
| `schedule_image.image_url` | string | Signed GCS URL. |

---

## 3. The detailed route + stops + polylines + schedule

```
GET /shuttle/public/route/map/no-page
```

Returns the same routes as above **plus** the `ls_bus_stop` array (the stops
on the route, in order, with polylines between them and the per-day schedule).

A real sample, normalized (truncated for readability):

```json
{
  "message": "Data got correctly",
  "meta": {},
  "data": [
    {
      "id": "01HNW8A6BWY6586Y1T10DV161D",
      "v_route_id": "ASG2",
      "name": "PIK 2 Millenials - PIK Avenue Via CBD",
      "code": "2",
      "path_color": "0xFFEC792D",
      "initial_lat": -6.055993,
      "initial_long": 106.696596,
      "initial_zoom": 2,
      "ls_bus_stop": [
        {
          "id": "01HNSB4PCDD7N3S4FVQ0Z4F135",
          "name": "Cluster Florida",
          "seq": 1,
          "lat_long": { "latitude": -6.030529, "longitude": 106.63742 },
          "ls_schedule": [
            {
              "id": "01K7GT2HJ6JV5SJ1ZG57R0QPXK",
              "day_enum": "FRIDAY",
              "arrival_time": "05:10:00",
              "seq": 1,
              "is_active": true
            }
          ],
          "ls_polyline": [
            { "lat": -6.030529, "long": 106.63742 },
            { "lat": -6.030527, "long": 106.637374 },
            { "lat": -6.030684, "long": 106.637367 }
          ]
        }
      ]
    }
  ]
}
```

### 3.1 Field reference for `RouteStop` (inside `ls_bus_stop`)

| Field | Type | Notes |
|---|---|---|
| `id` | string ULID | Stop's server-side ID. |
| `name` | string | Human stop name, e.g. `"Cluster Florida"`, `"Shelter PIK 2"`. |
| `seq` | int | 1-indexed order along the route. Use this to sort the route in your UI. |
| `lat_long.latitude` | float | Stop center latitude. |
| `lat_long.longitude` | float | Stop center longitude. |
| `ls_schedule` | array | The arrival time at this stop for each day of the week. |
| `ls_polyline` | array | The path the bus takes to **arrive at this stop** (lat/long pairs). Connect with the next stop's polyline to draw the full route. |

### 3.2 Field reference for `Schedule`

| Field | Type | Notes |
|---|---|---|
| `id` | string ULID | Server-side ID. |
| `day_enum` | string | One of `MONDAY`, `TUESDAY`, `WEDNESDAY`, `THURSDAY`, `FRIDAY`, `SATURDAY`, `SUNDAY`. |
| `arrival_time` | string | `HH:MM:SS` in **24-hour local time (WIB)**. The first/last bus of the day per stop is in this list. |
| `seq` | int | 1-indexed time slot at this stop. Use to order chronologically. |
| `is_active` | bool | Whether this time slot is currently in effect. |

> **Note:** the schedule is **per stop, per day**. The full timetable for a
> route is the union of all its stops' schedules. To get the "next bus
> from a stop at HH:MM on a given day", find all `ls_schedule` entries for
> that day at that stop, sort by `arrival_time`, and pick the first one
> ≥ the current time.

### 3.3 Field reference for `PolylinePoint`

| Field | Type | Notes |
|---|---|---|
| `lat` | float | Decimal degrees. |
| `long` | float | **The key is `long`, not `longitude`.** Inconsistent with the `lat_long` field used elsewhere. |

---

## 4. The standalone bus-stops endpoint

```
GET /shuttle/public/bus-stop/map/no-page
```

Returns every stop used by any Sedayu route, with the list of route IDs that
serve it. A real sample:

```json
{
  "message": "Data got correctly",
  "meta": {},
  "data": [
    {
      "id": "01K3N6TXW17KNZ2BKDSQARKPCQ",
      "name": "ASEAN",
      "lat_long": { "latitude": -6.23986, "longitude": 106.798973 },
      "ls_route_id": ["01K3MTNPEM8067Z39TMQDJWW6R"]
    },
    {
      "id": "01HTP5FDM587W08ZHE6FW90GP1",
      "name": "TZU CHI",
      "lat_long": { "latitude": -6.107935, "longitude": 106.739334 },
      "ls_route_id": [
        "01K3MTNPEM8067Z39TMQDJWW6R",   // T31
        "01HTP6K0JVKN72YRT7WH28EAPQ",   // ASG3
        "01JP9CH3NCD0TYYNYEZ4HW0T8R",   // PIK2-SCK
        "01HNW8A6BWY6586Y1T10DV161D",   // ASG2 — our shuttle
        "01K6CE3FXRXMPDHYQDGDADZC44",   // 1A
        "01K7GYXKV27RKK08Z9TT8BKZ3V",   // HC1
        "01HNMCRYDM9VMCQPM93TRS2MVM",   // ASG1
        "01K8SZZG2HCHEBA45XEFE6A2XA",   // ASG4
        "01K8TAKJQM40BVGZJWPZ095P6W"    // ASGW1
      ]
    }
  ]
}
```

> **Tzu Chi is the central interchange.** Nine routes touch it, including
> Felix's ASG2 shuttle. If you build an "is my bus here yet?" check, this is
> the most reliable stop to compare against.

---

## 5. Live bus positions — the WebSocket

```
URL:    wss://asgapis.sedayu.one/shuttle/busposition/v1/socket.io/?EIO=4&transport=websocket
Engine: Socket.IO v4 (Engine.IO v4, EIO=4)
```

There is **no subscription mechanism** — once you send `40` (Socket.IO
"connect"), the server immediately starts pushing `bus_position` events
for every active bus on the network. You don't need to subscribe, join a
room, or send any other event.

A real bus position event, normalized (stripped of the long signed image
URL):

```json
{
  "id": "01K742JW4HABB0Q8BQ9EPB01X9",
  "last_update": "2026-06-03T23:46:23.820749+07:00",
  "lat_long": { "latitude": -6.040127, "longitude": 106.696068 },
  "speed": 0.10,
  "v_vehicle_id": "TJ-557",
  "plate_number": "1B",
  "v_route_id": "ASG1",
  "route_id": "01HNMCRYDM9VMCQPM93TRS2MVM",
  "last_seq": 2,
  "last_bus_stop_id": "01K3N99FQFPZY8V1SKRKFVV1WE",
  "last_bus_stop_lat_long": { "latitude": -6.039916, "longitude": 106.696259 },
  "next_seq": 3,
  "next_bus_stop_id": "01JQ8QTR56C2TW1H7JDXGZFF0K",
  "next_bus_stop_lat_long": { "latitude": -6.045812, "longitude": 106.695451 },
  "eta_to_next_bus_stop": 4800.0,
  "range_to_next_bus_stop": 670.0
}
```

A bus with no `next_*` fields (like a TJ bus mirrored into the feed) looks
like:

```json
{
  "id": "01KCTZK1M24SRTGTKJA4MTB8F7",
  "last_update": "2026-06-03T23:05:52.564967+07:00",
  "lat_long": { "latitude": -6.31761, "longitude": 106.864995 },
  "speed": 0.10,
  "v_vehicle_id": "MYS-21286",
  "plate_number": "T31",
  "v_route_id": "T31",
  "route_id": "01K3MTNPEM8067Z39TMQDJWW6R",
  "last_seq": null,
  "last_bus_stop_id": null,
  "last_bus_stop_lat_long": null,
  "next_seq": null,
  "next_bus_stop_id": null,
  "next_bus_stop_lat_long": null,
  "eta_to_next_bus_stop": 0.0,
  "range_to_next_bus_stop": 0.0
}
```

### 5.1 Field reference for `BusPosition` event

| Field | Type | Notes |
|---|---|---|
| `id` | string ULID | Server-side record id. Use this as the dedup key. **Changes when the server resets the record.** |
| `last_update` | string (ISO 8601) | Timestamp with timezone offset (`+07:00` = WIB). |
| `lat_long.latitude` | float | Decimal degrees. |
| `lat_long.longitude` | float | Decimal degrees. |
| `speed` | float | km/h. Often 0 in dense areas (slow AVL sampling). |
| `v_vehicle_id` | string | Physical plate painted on the bus (e.g. `TJ-557`, `TJ-572`, `DMR-250300`, `MYS-21286`). Use this as the **stable** key across reconnects. |
| `plate_number` | string | **The bus plate prefix** — for Sedayu, matches the bus's `v_route_id` code (e.g. `2A` for a Sedayu ASG2 bus). **NOT a route code.** For mirrored TJ buses, this is the TJ route code (e.g. `T31`, `1A`). |
| `v_route_id` | string | Route slug. `ASG1`–`ASGSCK` for Sedayu; `T31`, `1A` for mirrored TransJakarta. |
| `route_id` | string ULID | Route's server-side id (foreign key to the route catalog). |
| `last_seq` | int\|null | 1-indexed position in the stop sequence the bus **just left**. `null` for mirrored TJ buses. |
| `last_bus_stop_id` | string ULID\|null | Foreign key to the `BusStop` table. |
| `last_bus_stop_lat_long` | object\|null | Stop's lat/long. Useful for drawing "you are here" markers. |
| `next_seq` | int\|null | 1-indexed position of the **next** stop the bus is heading to. |
| `next_bus_stop_id` | string ULID\|null | — |
| `next_bus_stop_lat_long` | object\|null | — |
| `eta_to_next_bus_stop` | float | **Seconds until arrival at the next stop.** 0 if unknown or for mirrored TJ buses. |
| `range_to_next_bus_stop` | float | **Meters to the next stop.** 0 if unknown. |
| `vehicle_image_url` | string | Signed GCS URL, 24 h expiry. Mirrored TJ buses use a different bucket path (`/shuttle/vendor/...`). |

### 5.2 The bus is approaching the next stop — use it!

`next_bus_stop_id` and `range_to_next_bus_stop` give you everything you need
to show "Bus 2A is 670 m from your stop, arriving in 80 seconds". This is
the **only** part of the API that's worth building a UX around — TJ's
mirrored buses don't have it.

### 5.3 Other WebSocket events

The app receives only `bus_position` and `bus_position_remove` events:

- `bus_position` — bus is active. Update your local state.
- `bus_position_remove` — bus has gone off-shift / out of range. Drop the
  record keyed by `id`.

The server also sends:
- `0{...}` — Engine.IO open packet with `sid`, `pingTimeout`, `pingInterval`
- `2` — Engine.IO ping (you must reply with `3` within `pingTimeout` ms,
  else the server disconnects)
- `40` — Socket.IO connect ack (after you send `40`)
- `40{...}` — Socket.IO connect ack with optional payload

`pingInterval` is typically 25 000 ms and `pingTimeout` is 20 000 ms. A simple
strategy: any time you see a `2`, immediately send `3` and continue. A
long-poll fallback exists but is much slower — always use `transport=websocket`
if you can.

---

## 6. Identifier types and key collisions — what to dedup on

When merging the Sedayu and TJ streams, dedup **on `v_vehicle_id`** (the
physical plate), not on `id` (the record id) and not on
`plate_number` (the prefix).

| Identifier | Sedayu | TJ (mirrored) | Stable? |
|---|---|---|---|
| `id` (ULID) | yes | yes | **No** — resets when the server creates a new record |
| `v_vehicle_id` (`TJ-572`, `MYS-21286`) | yes | yes | **Yes** — this is the physical plate, stable for the vehicle's life |
| `plate_number` (`2A`, `T31`) | yes | yes | **Yes for prefix, but ambiguous between operators** |
| `v_route_id` (`ASG2`, `T31`) | yes | yes | **Yes for the operator's route** |
| `route_id` (ULID) | yes | yes | **Yes** — server's stable record id |

The Sedayu stream will sometimes include buses whose `v_route_id` is
`T31` or `1A` — these are TransJakarta buses being mirrored. If you also
poll TJ, you'll get them twice. Dedup on `v_vehicle_id`.

---

## 7. Reference implementation

The `~/.hermes/scripts/pik2_shuttle.py` script (referenced by the
`pik2-shuttle-tracker` skill) is a working example of:

- Opening the Socket.IO v4 WebSocket
- Listening for 5–10 seconds of `bus_position` events
- Filtering by `v_route_id` (e.g. only `ASG2`) or by plate number (`2A`,
  `2B`, `2C`)
- Computing Haversine distance to a reference point
- Rendering an OSM-tile PNG with markers
- Caching the PNG to `~/.hermes/output/pik2_<timestamp>.png`

Read it alongside this doc to see the practical patterns.
