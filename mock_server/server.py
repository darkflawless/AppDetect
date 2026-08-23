"""
AppDetect Mock Server (FastAPI + UDP Video Receiver + Web Dashboard Hub)
Hỗ trợ đầy đủ 5 luồng hoạt động:
1. POST /api/verify: Xác thực khuôn mặt tài xế
2. WebSocket /ws/realtime: Điều khiển START/STOP Stream & nhận REALTIME_ALERT
3. UDP Receiver Port 9090: Nhận video 5 FPS từ camera buồng lái -> Stream MJPEG lên Web
4. POST /vehicle-logs: Nhận tọa độ GPS 5s/lần
5. POST /violations: Nhận vi phạm kèm ảnh bằng chứng Multipart
"""

import os
import time
import socket
import struct
import asyncio
import threading
from typing import List, Dict, Any, Optional
from datetime import datetime

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, File, UploadFile, Form, Query, Request
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# ── Cấu hình & Thư mục ────────────────────────────────────────────────────────
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
EVIDENCE_DIR = os.path.join(STATIC_DIR, "evidence")
os.makedirs(EVIDENCE_DIR, exist_ok=True)

app = FastAPI(title="AppDetect Mock Server", version="1.0.0")

# Cho phép CORS toàn bộ
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Quản lý trạng thái chia sẻ (State) ─────────────────────────────────────────
class ServerState:
    def __init__(self):
        self.latest_frame: Optional[bytes] = None
        self.frame_count: int = 0
        self.fps: float = 0.0
        self.last_frame_time: float = 0.0
        self.last_udp_driver_id: int = 0
        self.is_streaming: bool = False
        
        # Thống kê
        self.total_violations: int = 0
        self.latest_gps: Dict[str, Any] = {
            "driverId": 1, "latitude": 21.028511, "longitude": 105.854167, "speed": 0.0, "timestamp": int(time.time())
        }
        self.violation_history: List[Dict[str, Any]] = []

state = ServerState()

# ── Quản lý kết nối WebSocket ──────────────────────────────────────────────────
class ConnectionManager:
    def __init__(self):
        self.app_sockets: List[WebSocket] = []
        self.dashboard_sockets: List[WebSocket] = []

    async def connect_app(self, websocket: WebSocket):
        await websocket.accept()
        self.app_sockets.append(websocket)
        print(f"📱 [WS] App Android đã kết nối. Tổng số App: {len(self.app_sockets)}")
        await self.broadcast_to_dashboards({"type": "APP_STATUS", "status": "CONNECTED", "count": len(self.app_sockets)})

    async def connect_dashboard(self, websocket: WebSocket):
        await websocket.accept()
        self.dashboard_sockets.append(websocket)
        print(f"🖥️ [WS] Web Dashboard đã kết nối. Tổng số Dashboard: {len(self.dashboard_sockets)}")
        # Gửi ngay dữ liệu ban đầu
        await websocket.send_json({
            "type": "INIT_STATE",
            "appConnected": len(self.app_sockets) > 0,
            "isStreaming": state.is_streaming,
            "latestGps": state.latest_gps,
            "violations": state.violation_history[-10:]
        })

    def disconnect(self, websocket: WebSocket):
        if websocket in self.app_sockets:
            self.app_sockets.remove(websocket)
            print(f"📱 [WS] App Android đã ngắt kết nối. Còn lại: {len(self.app_sockets)}")
            asyncio.create_task(self.broadcast_to_dashboards({"type": "APP_STATUS", "status": "DISCONNECTED", "count": len(self.app_sockets)}))
        if websocket in self.dashboard_sockets:
            self.dashboard_sockets.remove(websocket)
            print(f"🖥️ [WS] Web Dashboard đã ngắt kết nối. Còn lại: {len(self.dashboard_sockets)}")

    async def send_to_apps(self, message: str):
        print(f"📤 [WS -> APP] Gửi lệnh: {message}")
        disconnected = []
        for ws in self.app_sockets:
            try:
                await ws.send_text(message)
            except Exception:
                disconnected.append(ws)
        for ws in disconnected:
            self.disconnect(ws)

    async def broadcast_to_dashboards(self, data: Dict[str, Any]):
        disconnected = []
        for ws in self.dashboard_sockets:
            try:
                await ws.send_json(data)
            except Exception:
                disconnected.append(ws)
        for ws in disconnected:
            self.disconnect(ws)

ws_manager = ConnectionManager()

# ── UDP Video Receiver (Port 9090) ────────────────────────────────────────────
def udp_receiver_thread():
    """Lắng nghe UDP Port 9090, bóc 8 byte Big-Endian DriverId + JPEG Payload."""
    UDP_IP = "0.0.0.0"
    UDP_PORT = 9090
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024) # 1MB buffer
    sock.bind((UDP_IP, UDP_PORT))
    print(f"📹 [UDP] Video Receiver đang lắng nghe trên {UDP_IP}:{UDP_PORT} ...")

    frame_counter = 0
    start_time = time.time()

    while True:
        try:
            data, addr = sock.recvfrom(65535)
            if len(data) < 8:
                continue

            # Bóc 8-byte big-endian driver_id
            driver_id = struct.unpack('>q', data[:8])[0]
            jpeg_bytes = data[8:]

            # Lưu frame
            state.latest_frame = jpeg_bytes
            state.last_udp_driver_id = driver_id
            state.frame_count += 1
            state.last_frame_time = time.time()
            state.is_streaming = True

            # Tính FPS
            frame_counter += 1
            elapsed = time.time() - start_time
            if elapsed >= 1.0:
                state.fps = round(frame_counter / elapsed, 1)
                frame_counter = 0
                start_time = time.time()

        except Exception as e:
            print(f"❌ [UDP] Lỗi nhận packet: {e}")
            time.sleep(0.01)

# Khởi chạy UDP Thread nền
udp_thread = threading.Thread(target=udp_receiver_thread, daemon=True)
udp_thread.start()

# ── REST API Endpoints ────────────────────────────────────────────────────────

@app.get("/")
async def root():
    return RedirectResponse(url="/static/dashboard.html")

# 1. POST /api/verify - Xác thực tài xế
@app.post("/api/verify")
async def verify_driver(
    image: Optional[UploadFile] = File(None),
    file: Optional[UploadFile] = File(None)
):
    upload_file = image or file
    filename = upload_file.filename if upload_file else "unknown.jpg"
    file_size = len(await upload_file.read()) if upload_file else 0
    print(f"🔑 [AUTH] POST /api/verify - Nhận ảnh xác thực ({filename}, {file_size} bytes)")
    
    # Mock nhận diện khuôn mặt thành công
    return {
        "verified": True,
        "driver_id": "1",
        "similarity": 0.965,
        "message": "Xác thực khuôn mặt tài xế thành công"
    }

# 2. POST /vehicle-logs - Nhận tọa độ GPS 5s/lần
class VehicleLogDTO(BaseModel):
    driverId: Optional[int] = 1
    vehicleId: Optional[str] = "1"
    latitude: float
    longitude: float
    speed: Optional[float] = 0.0
    timestamp: Optional[int] = None

@app.post("/vehicle-logs")
async def receive_vehicle_logs(request: Request):
    try:
        body = await request.json()
    except Exception:
        # Fallback nếu gửi Form
        form = await request.form()
        body = dict(form)

    lat = float(body.get("latitude", 21.028511))
    lng = float(body.get("longitude", 105.854167))
    speed = float(body.get("speed", 0.0))
    driver_id = body.get("driverId", 1)
    ts = body.get("timestamp", int(time.time() * 1000))

    gps_data = {
        "driverId": driver_id,
        "vehicleId": body.get("vehicleId", "1"),
        "latitude": lat,
        "longitude": lng,
        "speed": round(speed * 3.6, 1) if speed < 100 else round(speed, 1), # Chuyển km/h
        "timestamp": ts,
        "timeFormatted": datetime.now().strftime("%H:%M:%S")
    }
    state.latest_gps = gps_data
    print(f"📍 [GPS] POST /vehicle-logs: Lat={lat:.6f}, Lng={lng:.6f}, Speed={gps_data['speed']} km/h")

    # Bắn realtime lên Web Dashboard
    await ws_manager.broadcast_to_dashboards({
        "type": "GPS_UPDATE",
        "data": gps_data
    })

    return {"status": "SUCCESS", "message": "Log received", "timestamp": ts}

# 3. POST /violations - Nhận vi phạm kèm ảnh Multipart
@app.post("/violations")
async def receive_violation(
    driverId: Optional[int] = Form(1),
    violationType: str = Form(...),
    latitude: Optional[float] = Form(0.0),
    longitude: Optional[float] = Form(0.0),
    timestamp: Optional[int] = Form(None),
    image: Optional[UploadFile] = File(None)
):
    ts = timestamp or int(time.time() * 1000)
    image_url = None

    # Lưu ảnh bằng chứng nếu có
    if image:
        ext = os.path.splitext(image.filename)[1] or ".jpg"
        img_filename = f"evidence_{driverId}_{int(time.time())}_{violationType}{ext}"
        img_path = os.path.join(EVIDENCE_DIR, img_filename)
        
        content = await image.read()
        with open(img_path, "wb") as f:
            f.write(content)
        image_url = f"/static/evidence/{img_filename}"
        print(f"🚨 [VIOLATION] POST /violations: {violationType} | Lưu ảnh: {img_filename} ({len(content)} bytes)")
    else:
        print(f"🚨 [VIOLATION] POST /violations: {violationType} (Không kèm ảnh)")

    state.total_violations += 1
    violation_record = {
        "id": state.total_violations,
        "driverId": driverId,
        "violationType": violationType,
        "latitude": latitude,
        "longitude": longitude,
        "timestamp": ts,
        "timeFormatted": datetime.now().strftime("%H:%M:%S"),
        "imageUrl": image_url
    }
    state.violation_history.append(violation_record)

    # Bắn realtime cảnh báo & ảnh bằng chứng lên Dashboard
    await ws_manager.broadcast_to_dashboards({
        "type": "NEW_VIOLATION",
        "data": violation_record
    })

    return {
        "status": "SUCCESS",
        "violationId": state.total_violations,
        "message": f"Recorded violation {violationType}",
        "imageUrl": image_url
    }

# 4. POST /somnolence-records - Nhận log buồn ngủ (EAR/MAR)
@app.post("/somnolence-records")
async def receive_somnolence_record(request: Request):
    try:
        body = await request.json()
    except Exception:
        form = await request.form()
        body = dict(form)
    
    print(f"😴 [DROWSY-LOG] POST /somnolence-records: EAR={body.get('ear')}, MAR={body.get('mar')}, Duration={body.get('closedEyeDurationMs')}ms")
    await ws_manager.broadcast_to_dashboards({
        "type": "SOMNOLENCE_LOG",
        "data": body
    })
    return {"status": "SUCCESS", "message": "Somnolence record saved"}

# 5. GET /video_feed - MJPEG Live Stream cho Web Dashboard
@app.get("/video_feed")
def video_feed():
    def mjpeg_generator():
        # Trả về ảnh đen mặc định nếu chưa có stream
        blank_frame = None
        while True:
            # Nếu có frame mới trong 3 giây qua
            if state.latest_frame and (time.time() - state.last_frame_time < 3.0):
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + state.latest_frame + b'\r\n')
            else:
                state.is_streaming = False
                # Trả ảnh chờ 1 frame / 500ms
                time.sleep(0.5)
            time.sleep(0.05) # ~20 FPS max polling

    return StreamingResponse(
        mjpeg_generator(),
        media_type="multipart/x-mixed-replace; boundary=frame"
    )

# ── WebSocket Server Endpoint (/ws/realtime) ──────────────────────────────────
@app.websocket("/ws/realtime")
async def websocket_endpoint(
    websocket: WebSocket,
    type: str = Query("DASHBOARD"),
    driverId: Optional[int] = Query(1)
):
    client_type = type.upper()
    if client_type == "APP":
        await ws_manager.connect_app(websocket)
        try:
            while True:
                msg = await websocket.receive_text()
                print(f"📩 [WS <- APP] Nhận từ App: {msg}")
                # Nếu App gửi JSON alert
                try:
                    import json
                    data = json.loads(msg)
                    if data.get("type") == "REALTIME_ALERT" or "alertType" in data:
                        # Forward cho Dashboard
                        await ws_manager.broadcast_to_dashboards({
                            "type": "REALTIME_ALERT",
                            "data": data
                        })
                except Exception:
                    pass
        except WebSocketDisconnect:
            ws_manager.disconnect(websocket)
        except Exception as e:
            print(f"❌ [WS] Lỗi WebSocket App: {e}")
            ws_manager.disconnect(websocket)
    else:
        # Dashboard kết nối
        await ws_manager.connect_dashboard(websocket)
        try:
            while True:
                msg = await websocket.receive_text()
                print(f"📩 [WS <- DASHBOARD] Lệnh từ Dashboard: {msg}")
                # Nếu Dashboard ra lệnh START_STREAM / STOP_STREAM
                if "START_STREAM" in msg:
                    state.is_streaming = True
                    await ws_manager.send_to_apps("START_STREAM")
                elif "STOP_STREAM" in msg:
                    state.is_streaming = False
                    await ws_manager.send_to_apps("STOP_STREAM")
        except WebSocketDisconnect:
            ws_manager.disconnect(websocket)
        except Exception as e:
            print(f"❌ [WS] Lỗi WebSocket Dashboard: {e}")
            ws_manager.disconnect(websocket)

# ── Mount thư mục tĩnh ────────────────────────────────────────────────────────
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

if __name__ == "__main__":
    print("=" * 70)
    print("🚀 Khởi động AppDetect Mock Server trên http://0.0.0.0:8080")
    print("🖥️ Web Dashboard: http://localhost:8080/static/dashboard.html")
    print("📹 UDP Video Receiver: Port 9090")
    print("=" * 70)
    uvicorn.run(app, host="0.0.0.0", port=8080)
