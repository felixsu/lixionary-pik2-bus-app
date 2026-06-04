# TransJakarta API

Reverse-engineered from the **TJ mobile app** (`com.transjakmobile`,
`User-Agent: okhttp/4.12.0`, `X-App-Version: 2.10.2`).

The endpoints below are also documented by
[`mugnimaestra/tj-live`](https://github.com/mugnimaestra/tj-live) (SvelteKit
tracker that uses these same endpoints).

---

## 1. Base URL and global headers

```
Base URL:    https://tijeapi.transjakarta.co.id
User-Agent:  okhttp/4.12.0
X-App-OS:    android
X-App-Version: 2.10.2
X-Device-ID: <any stable string per deployment>
```

`X-Device-ID` **must be stable** — changing it forces a fresh guest login and
invalidates the JWT. The reference implementation uses a hardcoded string like
`tj31-lixsu-felix`.

All response bodies share this wrapper:

```json
{ "code": 200, "message": "success", "data": { ... } }
```

Errors use the same wrapper with non-200 `code` and a human-readable
`message`.

---

## 2. Authentication

There is no signup. Get a guest JWT in one call:

```http
POST /v1/auth/login/guest
Content-Type: application/json
X-App-OS: android
X-App-Version: 2.10.2
X-Device-ID: tj31-lixsu-felix
User-Agent: okhttp/4.12.0

{ "device_id": "tj31-lixsu-felix" }
```

Response:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOi...",
    "refresh_token": "eyJhbGciOi...",
    "expired_at": 1783000000
  }
}
```

- The access token is valid for ~30 days.
- The refresh token is valid for ~1 year.
- Decode the JWT (`exp` claim) to know when to refresh; refresh 60 s early
  to avoid races.
- Subsequent calls must include `Authorization: Bearer <jwt>`.

A full request, in curl:

```bash
TOKEN=$(curl -s -X POST "https://tijeapi.transjakarta.co.id/v1/auth/login/guest" \
  -H "Content-Type: application/json" \
  -H "X-App-OS: android" -H "X-App-Version: 2.10.2" \
  -H "X-Device-ID: tj31-lixsu-felix" -H "User-Agent: okhttp/4.12.0" \
  -d '{"device_id":"tj31-lixsu-felix"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
```

---

## 3. Endpoints used by this app

| Method | Path | What it returns |
|---|---|---|
| `GET` | `/v1/bus?latitude=&longitude=&radius=` | **All active buses** in a radius (meters) around a point. The core endpoint. |
| `GET` | `/v1/route?offset=&limit=` | Paginated route list. |
| `GET` | `/v1/route/{id}` | A single route with shape, stops, schedule. |
| `GET` | `/v1/bus_stop?latitude=&longitude=&radius=` | Nearby halte (BRT) and bus stops. |
| `GET` | `/v1/bus_stop/{id}/route` | Routes serving a given stop. |

### 3.1 The bus endpoint — what you actually want

```
GET /v1/bus
    ?latitude=-6.2021787
    &longitude=106.7997956
    &radius=2000
Authorization: Bearer <jwt>
```

**The radius is the killer parameter.** Jakarta-wide gives you ~4,000 buses
in a ~30 MB response. Use 500 m to 2 km for "buses near me" UX; the full
payload is wasteful.

Each bus record (real sample from the API):

```json
{
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
  "estimated_time_next_stops": 0,
  "estimated_distance_next_stops": 0,
  "curr_stops": "",
  "next_stops": "H00060P-Makasar",
  "prev_stops": "H00173P-Pinang Ranti",
  "livery": null,
  "stops": [
    {
      "name": "Petamburan",
      "stop_id": "G00726",
      "latitude": -6.201963,
      "longitude": 106.799812,
      "stop_sequence": 15,
      "time_from_prev": 0,
      "time_wait": 0,
      "headsign": "Pluit",
      "parent_stop_id": "H00036C",
      "parent_stop_name": "Petamburan",
      "eta": 0
    }
  ]
}
```

`stops` is the **per-bus pattern of every stop on the route**, not the
stops the bus is currently near. It is huge (avg 29 stops × 11 fields) and
accounts for ~97 % of the payload. **Strip it client-side** before storing or
logging anything.

### 3.2 The route endpoint

```
GET /v1/route?offset=0&limit=20
```

Returns a paginated list. A single route looks like:

```json
{
  "route_id": "T31",
  "agency_id": "",
  "route_short_name": "T31",
  "route_long_name": "PIK2 - Blok M",
  "route_desc": "Royaltrans",          // or "BRT" or "JAK.LINK" or "Rusun" or ""
  "route_type": 0,
  "route_color": "9c4782",              // hex WITHOUT the '#' prefix
  "route_text_color": "FFFFFF",
  "route_sort_order": 0,
  "price": 3500,                        // in IDR
  "operational_days": null,
  "trip_ids": ["T31-R01", "T31-R02"],
  "facilities": [
    {
      "name": "accessible for all",
      "title": "aksesibilitas umum",
      "icon": "https://filestorage.transjakarta.co.id/tije-apps/bus-stop/facilities/accessible_for_all.png",
      "icon_dark": "https://filestorage.transjakarta.co.id/tije-apps/bus-stop/facilities/accessible_for_all_dark.png"
    }
  ]
}
```

The `route_id` is a string like `"T31"`, `"1"`, `"2A"`, `"JAK.30"`, `"D31"`,
etc. **Leading characters are significant.**

---

## 4. Data shapes — every field

The full type/field list lives in [`03-data-schemas.md`](03-data-schemas.md)
under the "TransJakarta" section. The short version:

| Field | Type | Notes |
|---|---|---|
| `bus_body_no` | string | Physical plate painted on the bus, e.g. `"TJ-298"`, `"MYS-21278"`, `"PKT-108"`, `"DMR-250300"` |
| `route_code` | string | Route code shown to users, e.g. `"T31"`, `"9"`, `"2A"` |
| `route_name` | string | Human destination, e.g. `"Pluit"`, `"PIK 2"` |
| `latitude`, `longitude` | float | Decimal degrees, WGS84 |
| `speed` | int | km/h. Often 0 even when moving (slow AVL sampling). |
| `bearing` | int | Compass heading 0–359 |
| `timestamp` | string | **Epoch milliseconds as a string**, not a number. |
| `trip_id` | string | Like `"9-R01"`. Used to group a single vehicle's journey. |
| `direction` | string | Empty in most responses; sometimes `"0"` or `"1"`. |
| `trip_headsign` | string | Destination shown on the front of the bus. |
| `next_stops` | string | Pipe- or hyphen-delimited. Format: `"H00060P-Makasar"`. Stop IDs are uppercase H/B/G + 5 digits + 1 letter + digit. |
| `prev_stops` | string | Same format as `next_stops`. |
| `curr_stops` | string | Currently at this stop. Often empty. |
| `estimated_time_next_stops` | int | seconds; often 0 |
| `estimated_distance_next_stops` | int | meters; often 0 |
| `livery` | string\|null | Some buses have a sub-fleet identifier |
| `type` | string | `"BRT"`, `"JAK.LINK"`, `"Royaltrans"`, etc. |
| `stops` | array | The full per-bus route pattern. **Strip this in the client.** |

### Stop ID format (deep dive)

TransJakarta stop IDs follow a pattern:

- `H######X` — a **halte** (enclosed BRT station), e.g. `H00060P`
- `B#####XX` — a **bus stop** (regular feeder stop), e.g. `B02112P`
- `G#####` — a **group/parent** (e.g. `G00726`)

The trailing letter is a sub-platform indicator. Stop names are in
`parent_stop_name` for the human name.

---

## 5. Real-time streaming — MQTT (optional)

For high-frequency updates without polling, the mobile app uses Socket.IO
**and** MQTT. The TJ broker:

```
wss://mqtt.tj.co.id:8084/mqtt
```

Topic pattern: `/mobile_armada/XXXX-YYYY/#` (one topic per bus).

To discover topics, call `POST /v1/bus` with your lat/lng — the response
includes `mqtt_topics` and a `session_id`.

For our PIK 2 tracker we **don't need MQTT** — the polling REST endpoint is
fast enough at 1 Hz and much simpler to integrate. Use MQTT only if you're
building a fleet-wide real-time view.

---

## 6. Routes that matter for PIK 2

The TransJakarta corridors that touch PIK 2 are summarized below. Use the
`/v1/route?offset=&limit=` endpoint to refresh this list — the catalog has
~200 routes and changes occasionally.

| route_id | name | type | Operates in PIK 2? |
|---|---|---|---|
| `T31` | PIK 2 ↔ Blok M | BRT | **Yes — the only BRT directly serving PIK 2** |
| `11D` | Pulo Gebang ↔ Pulo Gadung via PIK | AUI | Yes — passes through PIK |
| `11R` | Rusun Cakung KM 2 ↔ Penggilingan via Rusun PIK | Rusun | Yes — passes PIK area |
| `1A` | Pantai Maju ↔ Balai Kota | BRT | Touches PIK 2 only marginally |
| `1`, `1K`, `1T` | Cibubur ↔ Balai Kota / Blok M | BRT + Royaltrans | No (passes south of PIK 2) |
| `2`, `2A`, `2B`, `2F`, `2H`, `2P`, `2Q` | Corridor 2 family | BRT | Central-east Jakarta, not PIK 2 |
| `JAK.06`–`JAK.118` | JAK.LINK feeders | JAK.LINK | A few pass PIK area |
| `B13`, `B14` | Royaltrans Bekasi | Royaltrans | No |
| `D31`, `D32` | Royaltrans Cinere | Royaltrans | No |

For the 30+ active Sedayu shuttles that *do* operate in PIK 2, see
[`02-pik2-sedayu-api.md`](02-pik2-sedayu-api.md).

---

## 7. Pitfalls (the things that will bite you)

1. **JWT lifetime is fuzzy.** Sometimes 30 days, sometimes less. Always
   check `exp` from the token itself, and handle 401 by re-authing once
   and retrying.

2. **`timestamp` is a string, not a number.** Decode with `int(timestamp)`
   when sorting by recency. JavaScript/JSON parsers will leave it as a
   string; `new Date(parseInt(ts))` in JS, `datetime.fromtimestamp(int(ts)/1000)` in Python.

3. **`stops` field is the elephant in the room.** 30 MB of data you
   probably don't need. Strip it before doing anything with the payload.
   The TJ-live wrapper drops it server-side and gets the payload to ~2 MB.

4. **`speed=0` is the default reading.** TJ's AVL feed samples slowly,
   and dense urban areas read as 0 most of the time. **Don't trust speed
   for ETA**. Use position and route pattern instead.

5. **`route_color` and `route_text_color` are hex WITHOUT `#`.** Add the `#`
   before using in CSS/HTML.

6. **`NearbyStop.distance` (when used elsewhere in the API) is in km,
   not meters.** But `estimated_distance_next_stops` and our own
   `radius` are in **meters**. Mind the unit.

7. **Route IDs are strings, not numbers.** `"2A"` ≠ `2`. URL-encode the
   path segment (`%32%41` for `2A`).

8. **No official status page.** If endpoints break, check the
   [tj-live issues](https://github.com/mugnimaestra/tj-live/issues) and
   the [TJ developer community](https://t.me/transjakarta_dev) (Telegram)
   for community reports.

9. **Don't try to "fix" the headers.** `X-App-OS`, `X-App-Version`,
   `User-Agent`, and `X-Device-ID` are all required. TJ rejects
   requests missing any of them.

10. **The whole payload can be `null` for inactive routes.** If you
    query with a small radius you may get a 200 with `"data": null` —
    handle it.

---

## 8. Reference implementation

The `~/.hermes/scripts/tj31.py` script (referenced by the `tj31-tracker`
skill) is a working example of:

- Getting a guest token
- Querying `/v1/bus` with a 2 km radius around the user's office
- Filtering by `route_code == "T31"`
- Computing Haversine distance to a reference point
- Rendering an OSM-tile PNG with markers
- Caching the PNG to `~/.hermes/output/tj31_<timestamp>.png`

Read it alongside this doc to see the practical patterns.
