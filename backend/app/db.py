import sqlite3
import json
from typing import List, Optional, Dict, Any

DB_PATH = "pik2_bus.db"

def get_db_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # Create route table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS route (
            id TEXT PRIMARY KEY,
            slug TEXT UNIQUE NOT NULL,
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            type TEXT NOT NULL,
            operator TEXT NOT NULL,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            color TEXT NOT NULL,
            initial_lat REAL,
            initial_lng REAL,
            initial_zoom INTEGER
        )
    """)
    
    # Create stop table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS stop (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL
        )
    """)
    
    # Create route_stop mapping table
    # A stop can appear multiple times in a route loop, so seq is part of primary key
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS route_stop (
            route_id TEXT NOT NULL,
            stop_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            polyline TEXT,
            schedule TEXT,
            PRIMARY KEY (route_id, stop_id, seq),
            FOREIGN KEY (route_id) REFERENCES route(id) ON DELETE CASCADE,
            FOREIGN KEY (stop_id) REFERENCES stop(id) ON DELETE CASCADE
        )
    """)
    
    conn.commit()
    conn.close()

def save_route(route_data: Dict[str, Any]):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO route (
            id, slug, code, name, type, operator, is_active, color, initial_lat, initial_lng, initial_zoom
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        route_data["id"],
        route_data["slug"],
        route_data["code"],
        route_data["name"],
        route_data["type"],
        route_data["operator"],
        1 if route_data.get("is_active", True) else 0,
        route_data["color"],
        route_data.get("initial_lat"),
        route_data.get("initial_lng"),
        route_data.get("initial_zoom")
    ))
    conn.commit()
    conn.close()

def save_stop(stop_data: Dict[str, Any]):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO stop (id, name, lat, lng)
        VALUES (?, ?, ?, ?)
    """, (
        stop_data["id"],
        stop_data["name"],
        stop_data["lat"],
        stop_data["lng"]
    ))
    conn.commit()
    conn.close()

def save_route_stop(route_id: str, stop_id: str, seq: int, polyline: Optional[List[Dict[str, float]]], schedule: Optional[List[Dict[str, Any]]]):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO route_stop (route_id, stop_id, seq, polyline, schedule)
        VALUES (?, ?, ?, ?, ?)
    """, (
        route_id,
        stop_id,
        seq,
        json.dumps(polyline) if polyline is not None else None,
        json.dumps(schedule) if schedule is not None else None
    ))
    conn.commit()
    conn.close()

def get_all_routes() -> List[Dict[str, Any]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM route")
    rows = cursor.fetchall()
    routes = [dict(row) for row in rows]
    conn.close()
    return routes

def get_route_by_slug(slug: str) -> Optional[Dict[str, Any]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM route WHERE slug = ?", (slug,))
    row = cursor.fetchone()
    route = dict(row) if row else None
    conn.close()
    return route

def get_route_stops(route_id: str) -> List[Dict[str, Any]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT rs.seq, rs.polyline, rs.schedule, s.id, s.name, s.lat, s.lng
        FROM route_stop rs
        JOIN stop s ON rs.stop_id = s.id
        WHERE rs.route_id = ?
        ORDER BY rs.seq ASC
    """, (route_id,))
    rows = cursor.fetchall()
    stops = []
    for row in rows:
        stop = {
            "id": row["id"],
            "name": row["name"],
            "location": {"lat": row["lat"], "lng": row["lng"]},
            "seq": row["seq"],
            "polyline": json.loads(row["polyline"]) if row["polyline"] else None,
            "schedule": json.loads(row["schedule"]) if row["schedule"] else None
        }
        stops.append(stop)
    conn.close()
    return stops
