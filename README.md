# Lixionary PIK2 Bus App

A real-time, private bus tracking system designed for residents and commuters of **Pantai Indah Kapuk 2 (PIK 2)**. The system aggregates position coordinates from two distinct transit networks:
1. **Agung Sedayu Group Shuttle** (Route `ASG2` - PIK 2 Millennials ↔ PIK Avenue via CBD)
2. **TransJakarta Corridor** (Route `T31` - PIK 2 ↔ Blok M)

It consists of a self-hosted **Python backend** (FastAPI) and a native **Android client** (Kotlin, Jetpack Compose, MapLibre SDK).

---

## 📂 Repository Structure

- `/backend` — Self-hosted Python FastAPI server that manages upstream WebSocket/REST feeds and streams unified real-time positions over Server-Sent Events (SSE).
- `/android-app` — Native Kotlin Android app with Jetpack Compose, Jetpack DataStore, and MapLibre/OpenStreetMap interactive maps.
- `/docs` — Comprehensive API documentation, unified schemas, and reverse-engineering findings.

---

## ⚡ Backend Setup & Execution (using `uv`)

The backend requires `uv` to build the environment and run the server.

### 1. Initialize Virtual Environment & Dependencies
Navigate to the `backend` directory, create the virtual environment, and install dependencies:
```bash
cd backend
uv venv
uv pip install -r requirements.txt
```

### 2. Run the Backend Server
Start the FastAPI application using the `uv run` command:
```bash
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```
Upon startup, the server automatically syncs static route map configurations and platform stop sequences from upstreams into a local SQLite database (`pik2_bus.db`).

### 3. Run Unit Tests
Run the unit test suite (testing normalizers, database safety parameters, and SQL injection mitigations):
```bash
uv run python tests/test_backend.py
```

## 🐳 Backend Setup & Execution (using Docker & Docker Compose)

Alternatively, you can run the backend service using Docker and Docker Compose. This maps the service to port `8020` and persists the SQLite database.

### 1. Build and Run using Docker Compose
From the project root:
```bash
docker compose up -d --build
```
Or from the `backend/` directory:
```bash
cd backend
docker compose up -d --build
```

### 2. Verify Health Status
Check container status and logs:
```bash
docker compose ps
docker compose logs -f
```
The FastAPI backend service will be accessible at `http://localhost:8020`. You can query the health endpoint:
```bash
curl http://localhost:8020/health
```

### 3. SQLite Database Persistence
The SQLite database is stored in a persistent Docker volume named `backend-data` mapped to `/app/data/pik2_bus.db` in the container.

---

### 4. Dynamic Connection Policy
The backend is designed to be **completely idle** until an active client connects to `/track`. 
- When you open the Android app to track routes, the backend automatically connects to the Sedayu Socket.IO feed and starts polling the TransJakarta REST API.
- When the app is closed, the backend disconnects from upstreams and falls back to an idle state.

---

## 📱 Android Client Setup & Execution

### 1. Build and Run in Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `/android-app` directory.
3. Wait for the Gradle project sync to complete.
4. Click the green **Run** button to deploy the app to your Emulator or connected physical device.

### 2. Setup the Backend URL
On first-time launch (or by tapping the **Gear Settings icon** in the top-right corner of the app):
- Input your backend base URL:
  - If using the **Android Emulator**, use the pre-populated host local loopback address: `http://10.0.2.2:8000/`.
  - If deploying to a **physical device** on your private VPN, input your server's VPN IP, for example: `http://192.168.1.100:8000/`.
- Select up to **3 routes** to track from the list of available routes loaded from the backend.
- Tap **"Save Config"** to persist settings to Jetpack DataStore.
