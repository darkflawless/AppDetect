# Tài Liệu Hợp Đồng Giao Tiếp API & Mapping Toàn Diện (Document-First)

> **Mục đích tài liệu**: Đối chiếu và bao quát **100% tất cả các API giữa Ứng dụng Android AppDetect và Backend Server (Spring Boot / OpenAPI)**, phân nhóm rõ ràng theo từng nghiệp vụ thực tế, đồng thời đánh dấu chi tiết trạng thái **[ĐÃ CÓ]** vs **[CHƯA CÓ - CẦN LÀM]**.

---

## 1. Bảng Tổng Hợp 3 Kênh Giao Tiếp & Hiện Trạng Codebase

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                ANDROID APPDETECT CLIENT                                │
├────────────────────────────┬─────────────────────────────┬─────────────────────────────┤
│ 1. UDP Video Stream        │ 2. WebSocket Realtime       │ 3. HTTP REST API            │
│    (Binary JPEG Frame)     │    (2-way JSON Event)       │    (CRUD & Nghiệp vụ đầy đủ)│
└──────────────┬─────────────┴──────────────┬──────────────┴──────────────┬──────────────┘
               │ udp://...:9090             │ ws://...:8080/ws/realtime   │ http://...:8080
               ▼                            ▼                             ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                  BACKEND SPRING BOOT                                   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

| Kênh truyền | Nhiệm vụ chính | Trạng thái Codebase | File tham chiếu trong Code |
| :--- | :--- | :--- | :--- |
| **UDP Streaming** (Port `9090`) | Nén JPEG 60%, throttle 5 FPS (200ms/frame), gửi packet `[8 bytes driverId] + [JPEG bytes]` | ✅ **[ĐÃ CÓ 100%]** | [`UdpFrameSender.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/UdpFrameSender.java) |
| **WebSocket** (`/ws/realtime`) | • Nhận lệnh `START_STREAM`, `STOP_STREAM`<br>• Gửi cảnh báo khẩn cấp `REALTIME_ALERT` khi AI phát hiện nguy hiểm | ⚠️ **[ĐÃ CÓ 50%]**<br>• Đã nhận lệnh START/STOP.<br>• **Chưa viết hàm gửi `REALTIME_ALERT` JSON.** | [`AppWebSocketClient.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/AppWebSocketClient.java) |
| **HTTP REST API** (Port `8080`) | Quản lý Đăng nhập, GPS Xe, Vi phạm, Ngủ gật, Chuyến đi, Sự cố khẩn cấp | ❌ **[CHƯA CÓ TRÊN APP]**<br>Backend đã có đầy đủ schema (OpenAPI). | `Chưa có package data.model & api client` |

---

## 2. Toàn Bộ Danh Mục HTTP REST API Từ Điện Thoại Lên Server (Trích Xuất OpenAPI)

Mọi API REST trả về từ Backend Spring Boot đều bọc trong cấu trúc chuẩn `ApiResponse<T>`:
```json
{
  "success": true,
  "message": "Thành công",
  "data": { ... },
  "timestamp": "2026-08-23T06:00:00Z",
  "errorCode": null
}
```

---

### 📍 Nhóm 1: Định Vị GPS & Trạng Thái Thiết Bị (Tracking & Telemetry)

#### 1.1. Gửi Tọa Độ GPS Xe Liên Tục (`POST /vehicle-logs`)
- **Mục đích**: Điện thoại lấy GPS định kỳ (mỗi 5 - 10s) gửi lên để Backend vẽ lộ trình xe thời gian thực.
- **Trạng thái**: 🔴 **[CHƯA CÓ TRONG CODE JAVA]** (Đã có quyền trong Manifest & `play-services-location`).
- **Request Body**:
```json
{
  "vehicle": {
    "id": "1"
  },
  "lat": 10.762622,
  "lng": 106.660172,
  "timeVehicleLog": "2026-08-23T06:10:00.000Z"
}
```

#### 1.2. Báo Cáo Trạng Thái Thiết Bị Gắn Trên Xe (`POST /devices` hoặc `PUT /devices/{id}`)
- **Mục đích**: Báo Server biết thiết bị Android đang hoạt động (`ACTIVE`) hay đã tắt/dừng ca (`STOPPED`).
- **Request Body**:
```json
{
  "id": "DEV_ANDROID_01",
  "deviceStatus": "ACTIVE",
  "startTime": "2026-08-23T06:00:00.000Z"
}
```

---

### 🚨 Nhóm 2: Sự Kiện AI & An Toàn Giao Thông (Safety & AI Events)

#### 2.1. Ghi Nhận Nhật Ký Ngủ Gật / Mệt Mỏi (`POST /somnolence-records`)
- **Nguồn dữ liệu**: Trích xuất từ [`DrowsinessDetector.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/DrowsinessDetector.java) (`ear`, `mar`, `closedEyeDurationMs`).
- **Trạng thái**: 🔴 **[CHƯA CÓ TRONG CODE]**
- **Request Body**:
```json
{
  "driver": {
    "id": "1"
  },
  "recordTime": "2026-08-23T06:15:30.000Z",
  "notes": "Tài xế nhắm mắt 3.2s (EAR: 0.14, MAR: 0.35)"
}
```

#### 2.2. Ghi Nhận Vi Phạm An Toàn Giao Thông (`POST /violations`)
- **Nguồn dữ liệu**: Trích xuất từ [`SeatBeltDetector.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/SeatBeltDetector.java) (`no-seatbelt`).
- **Trạng thái**: 🔴 **[CHƯA CÓ TRONG CODE]**
- **Request Body**:
```json
{
  "driver": {
    "id": "1"
  },
  "type": "NO_SEATBELT",
  "timeViolation": "2026-08-23T06:15:30.000Z",
  "note": "Phát hiện không thắt dây an toàn (Độ tin cậy: 88%)",
  "penalty": 0
}
```
*Enum `type`*: `NO_SEATBELT`, `PHONE_USE`, `SPEEDING`, `RED_LIGHT`, `LATE_ARRIVAL`.

#### 2.3. Ghi Nhận Kiểm Tra Nồng Độ Cồn (`POST /alcohol-records`)
- **Mục đích**: Ghi nhận kết quả kiểm tra cồn đầu ca của tài xế.
- **Request Body**:
```json
{
  "driver": {
    "id": "1"
  },
  "measurementTime": "2026-08-23T06:00:00.000Z",
  "alcoholLevel": 0.0,
  "notes": "Kiểm tra trước khi xuất bến: Đạt chuẩn 0.0 mg/L"
}
```

#### 2.4. Báo Cáo Sự Cố Khẩn Cấp Trên Đường (`POST /incidents`)
- **Mục đích**: Tài xế bấm nút báo sự cố (tai nạn, hỏng xe, thời tiết xấu).
- **Request Body**:
```json
{
  "trip": {
    "id": "TRIP_001"
  },
  "timeIncident": "2026-08-23T06:30:00.000Z",
  "type": "BREAKDOWN",
  "severity": "HIGH",
  "description": "Nổ lốp xe trên cao tốc"
}
```
*Enum `type`*: `ACCIDENT`, `BREAKDOWN`, `DELAY`, `WEATHER`, `PASSENGER`.  
*Enum `severity`*: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.

---

### 🚗 Nhóm 3: Quản Lý Chuyến Đi & Nhiên Liệu (Trip Management)

#### 3.1. Điểm Danh Tiến Trình Chuyến Đi (`POST /trip-progress`)
- **Request Body**:
```json
{
  "trip": {
    "id": "TRIP_001"
  },
  "order": 1,
  "arrive": "2026-08-23T06:00:00.000Z",
  "leave": "2026-08-23T06:15:00.000Z"
}
```

#### 3.2. Cập Nhật Trạng Thái Chuyến Đi (`PUT /trips/{id}`)
- **Request Body**:
```json
{
  "status": "IN_PROGRESS"
}
```
*Enum `status`*: `PLANNED`, `ASSIGNED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.

#### 3.3. Ghi Nhật Ký Đổ Xăng/Dầu (`POST /fuel-logs`)
- **Request Body**:
```json
{
  "vehicle": {
    "id": "1"
  },
  "time": "2026-08-23T07:00:00.000Z",
  "liter": 45.5,
  "unitPrice": 23500.0,
  "station": "Cây xăng Petrolimex Số 1"
}
```

---

### 🔑 Nhóm 4: Xác Thực & Thông Tin Tài Xế (Auth & Driver)

#### 4.1. Đăng Nhập Tài Khoản (`POST /auth/login`)
- **Request Body**:
```json
{
  "username": "driver_01",
  "password": "password123"
}
```
- **Response Data (`LoginResponse`)**:
```json
{
  "token": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "accountId": "ACC_001",
  "username": "driver_01",
  "role": "ROLE_DRIVER"
}
```

#### 4.2. Lấy Thông Tin Tài Xế (`GET /drivers/{id}`)
- Lấy thông tin xe phân công, bằng lái, trạng thái hoạt động.

---

## 3. Kênh WebSocket: Gửi Cảnh Báo Realtime (`REALTIME_ALERT`)

- **URL**: `ws://<SERVER_IP>:8080/ws/realtime?type=APP&driverId=<driverId>`
- **Payload App gửi lên Server khi AI phát hiện vi phạm**:
```json
{
  "type": "REALTIME_ALERT",
  "driverId": 1,
  "alertType": "DROWSY",
  "confidence": 0.95,
  "timestamp": 1787286330000,
  "details": {
    "ear": 0.14,
    "mar": 0.35,
    "headYaw": 5.2,
    "occupantCount": 2,
    "seatbeltViolations": 1,
    "durationMs": 3200
  }
}
```
*Enum `alertType`*: `DROWSY`, `YAWNING`, `DISTRACTED`, `FACE_MISSING`, `NO_SEATBELT_ALERT`.

---

## 4. Kênh UDP Video Stream (`UdpFrameSender`)

- **Trạng thái**: 🟢 **[ĐÃ HOÀN THIỆN 100%]**
- **File**: [`UdpFrameSender.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/UdpFrameSender.java)
- **Gói tin Binary**: `[8 bytes driverId (long)]` + `[JPEG bytes (60% quality)]` (Throttled 5 FPS).

---

## 5. Bảng Checklist Triển Khai Cho Developer

| STT | Nhiệm vụ | Kênh | File cần viết / sửa |
| :--- | :--- | :--- | :--- |
| 1 | Bổ sung hàm gửi `REALTIME_ALERT` | WebSocket | [`AppWebSocketClient.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/AppWebSocketClient.java) |
| 2 | Lấy GPS và gửi định kỳ `POST /vehicle-logs` | HTTP REST | Thêm GPS Tracker trong [`MainActivity.java`](file:///c:/Users/Admin/AndroidStudioProjects/AppDetect/app/src/main/java/com/example/myapplication/MainActivity.java) |
| 3 | Tạo cụm DTO Request/Response (`ApiResponse`, `SomnolenceRecord`, `Violation`, `VehicleLog`...) | Model | Tạo package `data.model` |
| 4 | Tạo REST Client gọi `POST /somnolence-records` & `POST /violations` | HTTP REST | Tạo `AppRestClient.java` |
