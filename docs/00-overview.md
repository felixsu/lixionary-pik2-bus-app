# Lixionary PIK2 Bus App — API Overview

This document is the entry point for the rest of the API docs. If you haven't
read anything else, read this.

---

## 1. What this project is

A real-time bus tracker app for residents and visitors of **PIK 2 (Pantai
Indah Kapuk 2)**, a large mixed-use township in north-west Jakarta operated by
**Agung Sedayu Group**.

The app aggregates two distinct data sources:

| Source | What it covers | How it's published |
|---|---|---|
| **TransJakarta** (`tijeapi.transjakarta.co.id`) | BRT corridors (1, 2, 2A, 7, T31, …), JAK.LINK feeders, Royaltrans, plus the T31 BRT that serves PIK 2 | Polling REST + optional MQTT stream |
| **PIK 2 Sedayu Shuttle** (`asgapis.sedayu.one`) | The internal Agung Sedayu shuttles: PIK 2 Millenials ↔ PIK Avenue (2A/2B/2C white/orange/green), Tokyo Riverside, Wisata PIK 2, etc. | Socket.IO v4 WebSocket (real-time push) |

Together these cover virtually every bus a PIK 2 resident can take.

---

## 2. The two APIs at a glance

| | TransJakarta | PIK 2 Sedayu |
|---|---|---|
| **Base URL** | `https://tijeapi.transjakarta.co.id` | `https://asgapis.sedayu.one/shuttle` |
| **Auth** | Guest JWT via `POST /v1/auth/login/guest` | None |
| **Data style** | REST + JSON, must poll | REST for static data, Socket.IO for live positions |
| **Live transport** | REST polling (1 req / few sec) | WebSocket (server pushes every position) |
| **Bus count** | ~4,000 active buses fleet-wide | ~30 active PIK 2 shuttles |
| **Coverage of PIK 2** | Only the T31 BRT serves PIK 2 directly | Every internal PIK 2 shuttle |
| **Rate limit** | Unknown, but be polite | Unknown, no observable limit at 1 Hz |
| **SLA** | None — unofficial reverse-engineered API | None — unofficial reverse-engineered API |
| **Documentation source** | `https://github.com/mugnimaestra/tj-live` (OpenAPI spec) | Reverse-engineered from `id.qluster.soc` Android app v5.1.1 |

**Both APIs are unofficial.** They were reverse-engineered from public mobile
apps and may break at any release. The app must handle changes gracefully.

---

## 3. The PIK 2 bus system in detail

The Agung Sedayu shuttle system is the **primary transit** for most PIK 2
residents, because it links the residential clusters (California, Florida,
Alabama, Tokyo Riverside, etc.) to PIK Avenue, the main commercial area.

### Sedayu internal routes (reverse-engineered, ASG* family)

| Code | v_route_id | Name | Physical bus plates |
|---|---|---|---|
| 1 | `ASG1` | Shelter PIK 2 ↔ PIK Avenue Via NICE | 1A, 1B, 1C |
| **2** | **`ASG2`** | **PIK 2 Millenials ↔ PIK Avenue Via CBD** | **2A, 2B, 2C** |
| 3 | `ASG3` | Tokyo Riverside Apt. ↔ PIK Avenue Via IDD | 3A, 3B, 3C |
| 4 | `Higer` | Shelter PIK 2 ↔ PIK Avenue Via Rasuna Said | 4A, 4B, 4C |
| W1 | `ASG5X` | Wisata PIK 2 | W1A, W1B |
| W2 | `ASG5` | Tokyo Riverside ↔ PIK Avenue | W2A, W2B |
| W3 | `ASG5X` | Pantai Maju ↔ GKT PIK2 | W3A, W3B |
| HC1 | `ASG4` | PIK 2 ↔ Juanda (commuter) | HC1A, HC1B |
| PIK2-SCK | `HIGER123` | PIK 2 ↔ Sedayu City Kelapa Gading | SCK-A, SCK-B |

> **Note on slugs:** the v_route_id slugs are not all "ASG*" as you might expect.
> `Higer` and `HIGER123` are vendor/operator codes, not "ASG" prefixes.
> Treat the `code` column as the stable user-facing identifier and the
> `v_route_id` as an opaque server slug. Always fetch the live list from
> `/public/route/no-page` on app startup rather than hard-coding it.

### The bus-plate naming trap ⚠️

The bus's `plate_number` field (e.g. `2A`, `1B`, `3C`) is the **physical plate
painted on the vehicle**, not a route code. This creates collisions with
TransJakarta:

- A Sedayu shuttle with `plate_number="2A"` runs on `v_route_id="ASG2"` (PIK 2
  Millenials ↔ PIK Avenue)
- A TransJakarta bus with `route_code="2A"` runs BRT Corridor 2A (Pulo Gadung
  ↔ Rawa Buaya)

**Always filter by `v_route_id` (ASG*) when you mean Sedayu, and by
`route_code` when you mean TransJakarta.** The codes look identical from a
distance.

### TransJakarta routes that touch PIK 2

| Code | Name | What it does |
|---|---|---|
| **T31** | PIK 2 ↔ Blok M | The only BRT corridor directly serving PIK 2. It runs the length of the corridor up to PIK 2, ~30 km north-west of central Jakarta. |
| 11D | Pulo Gebang ↔ Pulo Gadung via PIK | An "Angkutan Umum Integrasi" feeder that passes the PIK area. |
| 11R | Rusun Cakung KM 2 ↔ Penggilingan via Rusun PIK | A "Rusun" route. |
| 1A | Pantai Maju ↔ Balai Kota | An inner-city BRT corridor. Not actually PIK 2's 1A shuttle. |
| B13, B14, D31, D32, 1K, 1T, 6P | Royaltrans | Premium-fare long-distance Royaltrans lines; few touch PIK 2. |

---

## 4. Architecture of the app

```
                       ┌────────────────────────────┐
                       │  Hermes (this assistant)   │
                       │  + reverse-engineering     │
                       └────────────┬───────────────┘
                                    │ docs only
                       ┌────────────▼───────────────┐
                       │  /docs/  (Markdown)        │
                       │  +  /src/  (the app code)  │
                       └────────────┬───────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
   ┌──────────▼──────────┐ ┌────────▼────────┐ ┌─────────▼─────────┐
   │ TransJakarta client │ │  PIK 2 client   │ │ Cache / DB         │
   │ (REST polling)      │ │ (WebSocket)     │ │ (positions, stops) │
   └──────────┬──────────┘ └────────┬────────┘ └─────────┬─────────┘
              │                     │                     │
              └──────────┬──────────┴─────────────────────┘
                         │
                  ┌──────▼──────┐
                  │  UI / Map   │
                  │  (Telegram  │
                  │   / mobile) │
                  └─────────────┘
```

The app must:

1. **Authenticate** with TransJakarta on startup and refresh the JWT before it
   expires (~30 days, but refresh at 25 to be safe).
2. **Open** a Socket.IO v4 WebSocket to the PIK 2 shuttle server. There's no
   subscribe; the server pushes every active bus.
3. **Poll** TransJakarta `/v1/bus` for fleet-wide updates every N seconds.
4. **Cache** routes, stops, and the last known position of every bus.
5. **Present** the unified view in the UI.

---

## 5. Where to start

| If you want to… | Read |
|---|---|
| Understand the TransJakarta API | [`01-transjakarta-api.md`](01-transjakarta-api.md) |
| Understand the PIK 2 Sedayu API | [`02-pik2-sedayu-api.md`](02-pik2-sedayu-api.md) |
| See the exact JSON shape of every object | [`03-data-schemas.md`](03-data-schemas.md) |
| Get copy-pasteable curl / Python examples | [`04-quickstart.md`](04-quickstart.md) |

---

## 6. Provenance — how these APIs were discovered

### TransJakarta
- Public OpenAPI spec at https://raw.githubusercontent.com/mugnimaestra/tj-live/main/src/routes/api/openapi.yaml
- Live-tested on 2026-06-03 against `tijeapi.transjakarta.co.id`
- Polling endpoint returns ~4,000 active buses; full payload is ~30 MB but
  shrinks to ~2 MB if you strip the per-bus `stops` array
- See also: skill `transjakarta-api` (productivity) in `~/.hermes/skills/`

### PIK 2 Sedayu
- APK reverse-engineered from `id.qluster.soc` (Sedayu One City v5.1.1),
  downloaded as XAPK from APKPure on 2026-06-03
- The app's "shuttle" feature is a WebView loading
  `https://shuttle.sedayu.one?source=sedayu-one-app` — a Flutter Web build
  that talks to the same Socket.IO and REST endpoints
- API endpoints extracted by string-scanning `libapp.so` (16 MB ARM64 ELF
  containing the compiled Dart code)
- Live-tested the same day — confirmed all 30+ active shuttles are visible
  in real time with no auth required
- See also: skill `pik2-shuttle-tracker` (productivity) in `~/.hermes/skills/`
- The endpoint catalog and field names are documented as they appeared in
  the APK at that version; the server may rename things in future releases

---

## 7. Licensing and ethical use

Both APIs are unofficial and were reverse-engineered for personal use by
PIK 2 residents. Use them respectfully:

- Don't poll more than once per second.
- Don't redistribute the raw API responses publicly.
- Don't use them for a commercial product without contacting the operators.
- This project is for personal use; AGU and TJ are not affiliated.
