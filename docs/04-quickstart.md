# Quickstart — copy-pasteable examples

Every example is self-contained. Pick the one you need and run it.

**Prereqs** (already on this machine):
- `bash`, `curl`, `python3` (3.11+)
- Python packages: `requests`, `websocket-client`, `pydantic`

If you need them:
```bash
/home/orangepi/.local/share/uv/python/cpython-3.11.15-linux-aarch64-gnu/bin/python3 \
  -m pip install --break-system-packages requests websocket-client pydantic
```

The same Python interpreter is used throughout:
```bash
export PY=/home/orangepi/.local/share/uv/python/cpython-3.11.15-linux-aarch64-gnu/bin/python3
```

---

## 1. TransJakarta — get a guest token

```bash
curl -s -X POST "https://tijeapi.transjakarta.co.id/v1/auth/login/guest" \
  -H "Content-Type: application/json" \
  -H "X-App-OS: android" -H "X-App-Version: 2.10.2" \
  -H "X-Device-ID: tj31-lixsu-felix" -H "User-Agent: okhttp/4.12.0" \
  -d '{"device_id":"tj31-lixsu-felix"}'
```

Returns `data.token`. Save it, use it in `Authorization: Bearer <token>` for
all subsequent calls. Token lifetime ~30 days.

---

## 2. TransJakarta — list all routes (paginated)

```bash
TOKEN='paste-token-here'
curl -s "https://tijeapi.transjakarta.co.id/v1/route?offset=0&limit=20" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-App-OS: android" -H "X-App-Version: 2.10.2" \
  -H "X-Device-ID: tj31-lixsu-felix" -H "User-Agent: okhttp/4.12.0"
```

`offset` is the page start, `limit` is page size. The total is around 200
routes, so 10 pages of 20 is enough.

---

## 3. TransJakarta — list all buses in a 2 km radius (this is the big one)

```bash
TOKEN='paste-token-here'
# Around Felix's office in Tebet
curl -s "https://tijeapi.transjakarta.co.id/v1/bus?latitude=-6.2021787&longitude=106.7997956&radius=2000" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-App-OS: android" -H "X-App-Version: 2.10.2" \
  -H "X-Device-ID: tj31-lixsu-felix" -H "User-Agent: okhttp/4.12.0" \
  -o tj-buses-2km.json
ls -lh tj-buses-2km.json
# Note: this is ~30 MB because of the per-bus `stops` array
```

**Strip the `stops` field to shrink the response ~15×:**

```bash
python3 -c "
import json
d = json.load(open('tj-buses-2km.json'))
for b in d['data']:
    b.pop('stops', None)
json.dump(d, open('tj-buses-2km-stripped.json','w'), separators=(',',':'))
"
ls -lh tj-buses-2km-stripped.json   # should be ~2 MB
```

---

## 4. PIK 2 Sedayu — list all routes

```bash
curl -s "https://asgapis.sedayu.one/shuttle/public/route/no-page" \
  -o pik2-routes.json
python3 -c "
import json
for r in json.load(open('pik2-routes.json'))['data']:
    print(f\"  {r['code']:<6}  {r['v_route_id']:<10}  {r['name']}\")"
```

Output:
```
  1       ASG1        Shelter PIK 2 - PIK Avenue Via NICE
  2       ASG2        PIK 2 Millenials - PIK Avenue Via CBD
  3       ASG3        Tokyo Riverside Apt. - PIK Avenue Via IDD
  ...
```

---

## 5. PIK 2 Sedayu — list all bus stops

```bash
curl -s "https://asgapis.sedayu.one/shuttle/public/bus-stop/map/no-page" \
  -o pik2-stops.json
python3 -c "
import json
d = json.load(open('pik2-stops.json'))
print(f'{len(d[\"data\"])} stops')
for s in d['data'][:5]:
    ll = s['lat_long']
    print(f\"  {s['name']:<40}  ({ll['latitude']:.4f}, {ll['longitude']:.4f})  routes={len(s['ls_route_id'])}\")
"
```

---

## 6. PIK 2 Sedayu — get one route with stops + polylines + schedule

```bash
curl -s "https://asgapis.sedayu.one/shuttle/public/route/map/no-page" \
  -o pik2-routemap.json
python3 -c "
import json
d = json.load(open('pik2-routemap.json'))
for r in d['data']:
    if r.get('code') == '2':           # Felix's shuttle
        print(f\"Route: {r['name']}\")
        for s in r['ls_bus_stop'][:5]:
            ll = s['lat_long']
            print(f\"  seq={s['seq']}  {s['name']:<35}  ({ll['latitude']:.4f}, {ll['longitude']:.4f})\")
        break
"
```

---

## 7. PIK 2 Sedayu — live bus positions via WebSocket (the money shot)

```python
# live_pik2.py
import websocket, json, time

URL = "wss://asgapis.sedayu.one/shuttle/busposition/v1/socket.io/?EIO=4&transport=websocket"

ws = websocket.create_connection(URL, timeout=10)
print(ws.recv()[:80], "...")  # Engine.IO open packet
ws.send("40")                  # Socket.IO connect

# Listen for 10 seconds, collect unique bus positions
seen = {}
ws.settimeout(1.0)
deadline = time.time() + 10
while time.time() < deadline:
    try:
        raw = ws.recv()
    except Exception:
        continue
    if not raw.startswith("42"):
        continue  # skip ping ('2') and ack ('40')
    try:
        data = json.loads(raw[2:])
        if data[0] != "bus_position":
            continue
        b = data[1]
        # Key on vehicle_id (stable) not id (resets)
        vid = b["v_vehicle_id"]
        seen[vid] = b
    except Exception:
        continue

ws.close()

# Print: route_slug, plate, vehicle_id, position, ETA to next stop
for b in sorted(seen.values(), key=lambda x: x.get("v_route_id", "")):
    ll = b["lat_long"]
    eta = b.get("eta_to_next_bus_stop", 0)
    rng = b.get("range_to_next_bus_stop", 0)
    print(f"  {b['v_route_id']:<8} plate={b['plate_number']:<5} "
          f"vehicle={b['v_vehicle_id']:<10} "
          f"({ll['latitude']:.5f},{ll['longitude']:.5f}) "
          f"speed={b['speed']:>4.0f} "
          f"→ next: {rng:>5.0f}m / {eta:>4.0f}s")
```

Run:
```bash
$PY live_pik2.py
```

Expected output (truncated):
```
  ASG1    plate=1A     vehicle=TJ-554     (-6.03991, 106.69605)  speed=  0 → next:   717m / 5160s
  ASG2    plate=2A     vehicle=TJ-555     (-6.25270, 106.87613)  speed=  0 → next:     0m /    0s
  ASG2    plate=2C     vehicle=TJ-572     (-6.18435, 106.73051)  speed=  0 → next:     0m /    0s
  T31     plate=T31    vehicle=MYS-21286  (-6.31761, 106.86499)  speed=  0 → next:     0m /    0s
  ...
```

---

## 8. Combined client — both APIs in one Python script

```python
# tracker.py — minimal end-to-end example
import json
import time
import requests
import websocket

# ---- TransJakarta: get token, poll /v1/bus ----
def tj_token() -> str:
    r = requests.post(
        "https://tijeapi.transjakarta.co.id/v1/auth/login/guest",
        headers={
            "X-App-OS": "android", "X-App-Version": "2.10.2",
            "X-Device-ID": "pik2-bus-app", "User-Agent": "okhttp/4.12.0",
            "Content-Type": "application/json",
        },
        json={"device_id": "pik2-bus-app"},
        timeout=15,
    )
    r.raise_for_status()
    return r.json()["data"]["token"]


def tj_buses(token: str, lat: float, lng: float, radius: int = 2000):
    r = requests.get(
        "https://tijeapi.transjakarta.co.id/v1/bus",
        params={"latitude": lat, "longitude": lng, "radius": radius},
        headers={
            "Authorization": f"Bearer {token}",
            "X-App-OS": "android", "X-App-Version": "2.10.2",
            "X-Device-ID": "pik2-bus-app", "User-Agent": "okhttp/4.12.0",
        },
        timeout=15,
    )
    r.raise_for_status()
    # Strip the heavy `stops` array
    for b in r.json()["data"]:
        b.pop("stops", None)
    return r.json()["data"]


# ---- PIK 2 Sedayu: WebSocket subscribe ----
def pik2_positions(window_seconds: int = 10):
    url = "wss://asgapis.sedayu.one/shuttle/busposition/v1/socket.io/?EIO=4&transport=websocket"
    ws = websocket.create_connection(url, timeout=10)
    ws.recv()  # engine.io open
    ws.send("40")
    seen = {}
    deadline = time.time() + window_seconds
    ws.settimeout(1.0)
    while time.time() < deadline:
        try:
            raw = ws.recv()
        except Exception:
            continue
        if not raw.startswith("42"):
            continue
        try:
            d = json.loads(raw[2:])
            if d[0] != "bus_position":
                continue
            seen[d[1]["v_vehicle_id"]] = d[1]
        except Exception:
            continue
    ws.close()
    return list(seen.values())


# ---- Main ----
if __name__ == "__main__":
    # TransJakarta: poll once around the office
    tok = tj_token()
    print(f"TJ token length: {len(tok)}")
    tj = tj_buses(tok, -6.2021787, 106.7997956)
    print(f"TJ buses within 2 km: {len(tj)}")
    for b in tj[:3]:
        print(f"  {b['bus_body_no']:<10}  route {b['route_code']:<5}  {b['route_name']}")

    # PIK 2 Sedayu: listen for 10 seconds
    print("\nListening to PIK 2 WebSocket for 10 s...")
    pik2 = pik2_positions(10)
    print(f"PIK 2 unique buses: {len(pik2)}")
    for b in sorted(pik2, key=lambda x: x["v_route_id"])[:5]:
        print(f"  {b['v_route_id']:<8} plate={b['plate_number']:<5}  {b['v_vehicle_id']}")
```

Run:
```bash
$PY tracker.py
```

---

## 9. End-to-end test: are the 2A/2B/2C buses on the road?

```python
import websocket, json, time
url = "wss://asgapis.sedayu.one/shuttle/busposition/v1/socket.io/?EIO=4&transport=websocket"
ws = websocket.create_connection(url, timeout=10)
ws.recv(); ws.send("40")
seen_plates = set()
ws.settimeout(1.0)
deadline = time.time() + 15
while time.time() < deadline:
    try: raw = ws.recv()
    except: continue
    if not raw.startswith("42"): continue
    try:
        d = json.loads(raw[2:])
        if d[0] == "bus_position":
            b = d[1]
            if b.get("v_route_id") == "ASG2":
                seen_plates.add(b.get("plate_number"))
    except: continue
ws.close()
expected = {"2A", "2B", "2C"}
print(f"Expected plates: {sorted(expected)}")
print(f"Observed plates: {sorted(seen_plates)}")
print(f"Missing: {sorted(expected - seen_plates) or 'none'}")
```

Sample output:
```
Expected plates: ['2A', '2B', '2C']
Observed plates: ['2A', '2C']
Missing: ['2B']                              # 2B might be off-shift
```

---

## 10. From your existing scripts

The repo's existing scripts are the most battle-tested reference:

- `~/.hermes/scripts/tj31.py` — TransJakarta (T31, around Tebet office)
- `~/.hermes/scripts/pik2_shuttle.py` — PIK 2 Sedayu (all ASG* routes)

Both render an OSM-tile PNG map and print a text summary. They can be
adapted to a web backend by:
1. Replacing `print(...)` with a JSON response.
2. Replacing `fig.savefig(...)` with rendering to a base64 string or
   uploading to S3 / GCS.
3. Replacing the `input()` parsing with FastAPI / Flask routes.
