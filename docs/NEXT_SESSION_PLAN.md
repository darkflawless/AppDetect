# Kế Hoạch Phiên Tới: Xây Dựng Mock Server Kiểm Thử Tích Hợp (End-to-End Verification)

## 🎯 Mục Tiêu
Xây dựng một **Mock Server tích hợp siêu nhẹ (viết bằng Python FastAPI)** chạy trực tiếp trên máy tính để kiểm thử và xác nhận toàn bộ 5 luồng hoạt động của ứng dụng **Android AppDetect**.

---

## 🛠️ Các Thành Phần Mock Server Sẽ Triển Khai

```
mock_server/
│
├── server.py              # Server chính chạy FastAPI + Uvicorn
│   ├── /api/verify        # (Kênh 1) POST nhận ảnh multipart xác thực tài xế
│   ├── /ws/realtime       # (Kênh 2) WebSocket nhận alert & gửi lệnh START/STOP stream
│   ├── /vehicle-logs      # (Kênh 4) POST nhận tọa độ GPS định kỳ 5s/lần
│   ├── /violations        # (Kênh 5) POST multipart nhận vi phạm kèm ảnh bằng chứng
│   └── /somnolence-records# (Kênh 5) POST nhận nhật ký ngủ gật (EAR, MAR)
│
├── udp_receiver.py        # (Kênh 3) Lắng nghe UDP Port 9090, bóc 8-byte driverId & hiển thị FPS video
└── static/dashboard.html   # Web Dashboard mini xem live video camera và bản đồ GPS thời gian thực
```

---

## 📋 5 Kịch Bản Kiểm Thử Sẽ Chạy (Verification Steps)

| Bước | Hành động trên App | Kỳ vọng trên Mock Server |
| :---: | :--- | :--- |
| **1** | Mở App, quét mặt tại `DriverVerifyActivity` | Server nhận `POST /api/verify`, trả về `verified: true`. App chuyển sang `DriverMonitorActivity`. |
| **2** | Vào buồng lái | WebSocket kết nối thành công (`type=APP&driverId=1`). |
| **3** | Bấm "Bắt đầu chuyến đi" | GPS Service chạy nền, Server nhận `POST /vehicle-logs` đều đặn mỗi 5 giây (`lat`, `lng`, `speed`). |
| **4** | Server gửi lệnh `START_STREAM` | `UdpFrameSender` mở van, `udp_receiver.py` nhận đều đặn 5 FPS video JPEG từ điện thoại. |
| **5** | Thử nhắm mắt / không đeo dây an toàn | Server nhận được `REALTIME_ALERT` qua WebSocket và nhận `POST /violations` kèm ảnh chụp bằng chứng. |

---

## 🚀 Cách Bắt Đầu Ở Phiên Sau
Ở phiên chat tiếp theo, bạn chỉ cần gõ: **"Bắt đầu dựng Mock Server theo plan"** ➔ Tôi sẽ bắt tay tạo ngay bộ Mock Server đầy đủ kèm Web Dashboard và hướng dẫn bạn test từ A đến Z!
