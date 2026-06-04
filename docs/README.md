# Lixionary PIK2 Bus App — Documentation

Real-time bus tracker for **PIK 2 (Pantai Indah Kapuk 2)** that aggregates
two unofficial APIs:

1. **TransJakarta** — public BRT (T31 serves PIK 2 directly)
2. **PIK 2 Sedayu Shuttle** — internal Agung Sedayu shuttles
   (2A/2B/2C white/orange/green running PIK 2 Millenials ↔ PIK Avenue)

---

## 📚 Read the docs in this order

| # | File | What it covers |
|---|---|---|
| 00 | [`00-overview.md`](00-overview.md) | Project intro, both APIs compared, architecture diagram, route catalog summary, naming traps |
| 01 | [`01-transjakarta-api.md`](01-transjakarta-api.md) | Full TransJakarta docs — auth, endpoints, every field, pitfalls |
| 02 | [`02-pik2-sedayu-api.md`](02-pik2-sedayu-api.md) | Full PIK 2 Sedayu docs — REST + WebSocket, every field, dedup strategy |
| 03 | [`03-data-schemas.md`](03-data-schemas.md) | The unified schema (TypeScript, Pydantic, SQL) — copy/paste into your code |
| 04 | [`04-quickstart.md`](04-quickstart.md) | Copy-pasteable `curl` and Python examples for every endpoint |

---

## 🎯 What you can build from here

| Goal | Start with |
|---|---|
| Show "where's the 2A right now" | `04-quickstart.md` §7 (Pydantic script) + §3 data schemas |
| Show "what buses are near me on the map" | Combine §3 (TJ) + §7 (Sedayu) and dedup on `vehicle_id` |
| Show "when does the next 2A arrive at my stop" | `02-pik2-sedayu-api.md` §5 — `next_bus_stop_id` + `eta_to_next_bus_stop` |
| "Find me a route from PIK 2 to Blok M" | `01-transjakarta-api.md` §3.1 (T31) + §6 (route list) |
| Build a Telegram bot for PIK 2 buses | `04-quickstart.md` + reuse `~/.hermes/scripts/pik2_shuttle.py` |
| Build a mobile app with live map | `03-data-schemas.md` TypeScript section + the WebSocket client in `04-quickstart.md` §7 |

---

## ⚠️ Important naming trap

A bus with `plate_number="2A"` is the **Sedayu internal shuttle** on
`v_route_id="ASG2"`. A bus with `route_code="2A"` is **TransJakarta BRT
Corridor 2A** (Pulo Gadung ↔ Rawa Buaya). **Different operators, different
routes, same number.**

Always identify Sedayu by `v_route_id` starting with `ASG` (e.g. `ASG2`),
and TransJakarta by `route_code` (e.g. `T31`, `1A`).

See `00-overview.md` §3.2 for the full table of routes and their physical
bus plates.

---

## 🛠️ Reference implementations (already working)

These scripts live outside this repo but are the most-tested examples:

- `~/.hermes/scripts/tj31.py` — TransJakarta T31 tracker (Tebet office)
- `~/.hermes/scripts/pik2_shuttle.py` — PIK 2 Sedayu shuttle tracker

Both render an OSM-tile PNG map and print a text summary. They can be
adapted to a web backend or wrapped in a Telegram bot.

---

## 🧪 API stability & SLA

**Both APIs are unofficial** — reverse-engineered from public mobile apps.
Neither has an SLA. The endpoints, field names, and data formats may change
at any release. The app must handle:

- Missing fields (always optional in practice)
- New fields (don't break on unknown keys)
- 403/404 on outdated endpoints
- Schema drift (Sedayu ULIDs vs TJ plain string IDs)

When something breaks, check:

- For TJ: https://github.com/mugnimaestra/tj-live/issues
- For Sedayu: the source app at `id.qluster.soc` on Google Play; reverse-
  engineer any new version the same way we did v5.1.1.

---

## 📝 License

This documentation is for personal use by PIK 2 residents. The data
sources are not affiliated with this project.
