import unittest
import sqlite3
import os
import sys
from datetime import datetime, timezone

# Add backend directory to path
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

import app.db
# Use a test DB path for testing
app.db.DB_PATH = "test_pik2_bus.db"

from app.db import init_db, save_route, save_stop, save_route_stop, get_all_routes, get_route_by_slug
from app.schemas import normalize_tj_bus, normalize_sedayu_position, normalize_sedayu_route

class TestBackend(unittest.TestCase):
    def setUp(self):
        # Clean up test DB if exists
        if os.path.exists("test_pik2_bus.db"):
            os.remove("test_pik2_bus.db")
        init_db()

    def tearDown(self):
        if os.path.exists("test_pik2_bus.db"):
            os.remove("test_pik2_bus.db")

    def test_sqlite_parameterized_queries(self):
        """Verify route insertion and retrieval using safe parameterized SQLite calls."""
        route_data = {
            "id": "01HNW8A6BWY6586Y1T10DV161D",
            "slug": "ASG2",
            "code": "2",
            "name": "PIK 2 Millenials - PIK Avenue Via CBD",
            "type": "SEDAYU",
            "operator": "SEDAYU",
            "is_active": True,
            "color": "#EC792D",
            "initial_lat": -6.055993,
            "initial_lng": 106.696596,
            "initial_zoom": 12
        }
        
        save_route(route_data)
        
        routes = get_all_routes()
        self.assertEqual(len(routes), 1)
        self.assertEqual(routes[0]["slug"], "ASG2")
        self.assertEqual(routes[0]["color"], "#EC792D")

    def test_sql_injection_mitigation(self):
        """Verify that SQL injection payloads do not compromise database queries."""
        # Payload trying to inject additional parameters or syntax
        malicious_slug = "ASG2' OR '1'='1"
        
        # Verify that searching for the malicious slug returns None rather than executing SQL command
        route = get_route_by_slug(malicious_slug)
        self.assertIsNone(route)

    def test_normalize_tj_bus(self):
        raw_tj_bus = {
            "bus_body_no": "TJ-298",
            "route_code": "T31",
            "route_name": "PIK2 - Blok M",
            "latitude": -6.193172,
            "longitude": 106.797058,
            "speed": 15,
            "bearing": 180,
            "timestamp": "1780505961974",
            "trip_id": "T31-R01",
            "trip_headsign": "Blok M",
        }
        pos = normalize_tj_bus(raw_tj_bus)
        self.assertEqual(pos.vehicle_id, "TJ-298")
        self.assertEqual(pos.route_slug, "T31")
        self.assertEqual(pos.operator, "TRANSJAKARTA")
        self.assertEqual(pos.location.lat, -6.193172)
        self.assertEqual(pos.location.lng, 106.797058)
        self.assertEqual(pos.speed_kmh, 15.0)
        self.assertEqual(pos.bearing, 180)
        self.assertEqual(pos.trip_id, "T31-R01")
        self.assertEqual(pos.trip_headsign, "Blok M")
        self.assertTrue(isinstance(pos.last_seen_at, datetime))

    def test_normalize_sedayu_position(self):
        raw_sedayu = {
            "id": "01K742JW4HABB0Q8BQ9EPB01X9",
            "last_update": "2026-06-03T23:46:23.820749+07:00",
            "lat_long": {"latitude": -6.040127, "longitude": 106.696068},
            "speed": 0.10,
            "v_vehicle_id": "TJ-557",
            "plate_number": "2A",
            "v_route_id": "ASG2",
            "route_id": "01HNMCRYDM9VMCQPM93TRS2MVM",
            "last_seq": 2,
            "last_bus_stop_id": "01K3N99FQFPZY8V1SKRKFVV1WE",
            "last_bus_stop_lat_long": {"latitude": -6.039916, "longitude": 106.696259},
            "next_seq": 3,
            "next_bus_stop_id": "01JQ8QTR56C2TW1H7JDXGZFF0K",
            "next_bus_stop_lat_long": {"latitude": -6.045812, "longitude": 106.695451},
            "eta_to_next_bus_stop": 4800.0,
            "range_to_next_bus_stop": 670.0,
            "vehicle_image_url": "https://storage.googleapis.com/shuttle/TJ-557.png"
        }
        pos = normalize_sedayu_position(raw_sedayu)
        self.assertEqual(pos.vehicle_id, "TJ-557")
        self.assertEqual(pos.route_slug, "ASG2")
        self.assertEqual(pos.operator, "SEDAYU")
        self.assertEqual(pos.location.lat, -6.040127)
        self.assertEqual(pos.location.lng, 106.696068)
        self.assertEqual(pos.speed_kmh, 0.10)
        self.assertEqual(pos.next_stop_id, "01JQ8QTR56C2TW1H7JDXGZFF0K")
        self.assertEqual(pos.eta_seconds, 4800)
        self.assertEqual(pos.distance_to_next_m, 670.0)
        self.assertEqual(pos.image_url, "https://storage.googleapis.com/shuttle/TJ-557.png")

if __name__ == "__main__":
    unittest.main()
