# Hướng dẫn cấu hình IP cho ứng dụng AppDetect

Để ứng dụng có thể kết nối được với server FastAPI chạy trên máy tính của bạn, bạn cần đảm bảo cả điện thoại và máy tính chạy cùng một mạng Wi-Fi và cấu hình đúng địa chỉ IP.

## Bước 1: Tìm địa chỉ IP máy tính của bạn
1. Trên máy tính (Windows), nhấn phím `Windows + R`, gõ `cmd` và nhấn Enter.
2. Trong cửa sổ dòng lệnh, gõ: `ipconfig`
3. Tìm dòng **IPv4 Address** trong mục Wi-Fi (Ví dụ: `192.168.1.15`). Đây chính là địa chỉ IP bạn cần dùng.

## Bước 2: Cập nhật IP trong Code Android
1.a Mở file: `app/src/main/java/com/example/myapplication/DriverVerifyActivity.java`

1.b Mở file `res/xml/network_security_config.xml`

2.a Tìm dòng code định nghĩa `BASE_URL` (thường ở gần đầu file):
   ```java
   private static final String BASE_URL = "http://192.168.x.x:8000";
   ```
   
2.b tìm dòng code **<domain includeSubdomains="true">192.168.x.x</domain>**

3. Thay đổi `192.168.x.x` thành địa chỉ IPv4 bạn vừa tìm được ở Bước 1.
   *Ví dụ:* `private static final String BASE_URL = "http://192.168.1.15:8000";`
4. Lưu file và chạy lại ứng dụng (Build & Run).

## Bước 3: Lưu ý quan trọng trên Server (FastAPI)
Khi chạy server FastAPI trên máy tính, bạn phải cho phép nó nhận kết nối từ các thiết bị khác trong mạng bằng cách bind vào địa chỉ `0.0.0.0`.

Lệnh chạy server nên là:
```bash
uvicorn main:app --host 0.0.0.0 --port 8000
```
**(bước 3 này được chạy bên coreAI, hướng dẫn run server sẽ bên readme bên đó)**
## Kiểm tra kết nối
* Đảm bảo điện thoại **không** dùng 4G/5G khi đang test nội bộ.
* Đảm bảo Firewall (tường lửa) trên máy tính không chặn cổng `8000`.
* Bạn có thể kiểm tra bằng cách mở trình duyệt trên điện thoại và truy cập thử vào địa chỉ IP đó (Ví dụ: `http://192.168.1.15:8000/docs`). Nếu hiện trang tài liệu FastAPI là kết nối đã thông suốt.


API : .

📱 Danh Sách API Cho Ứng Dụng Điện Thoại (Mobile App)
1. 🔐 Đăng Nhập & Xác Thực (Authentication)
POST /auth/login: Tài xế / Nhân viên đăng nhập từ điện thoại (nhận JWT Token).
2. 📍 Gửi Tọa Độ & Dữ Liệu An Toàn Từ Điện Thoại Lên Server
POST /vehicle-logs: Điện thoại gửi tọa độ GPS định kỳ (lat, lng, timeVehicleLog).
POST /somnolence-records: Gửi cảnh báo phát hiện buồn ngủ (từ camera AI trên điện thoại).
POST /alcohol-records: Gửi kết quả đo nồng độ cồn của tài xế (alcoholLevel, measurementTime).
POST /incidents: Tài xế báo cáo sự cố trên đường (Tai nạn, hỏng xe, tắc đường...).
POST /violations: Báo cáo vi phạm 
POST /fuel-logs: Tài xế nhập nhật ký đổ xăng (liter, unitPrice, station).
3. 🛣️ Quản Lý Chuyến Đi (Tài Xế Thao Tác Trên App)
GET /assignments: Xem danh sách xe & chuyến đi được phân công cho tài xế.
GET /trips/{id}: Xem chi tiết lộ trình chuyến đi hiện tại.
POST /trip-progress: Cập nhật tiến độ chuyến đi (bấm nút "Đến trạm" / "Rời trạm").
PUT /trips/{id}: Cập nhật trạng thái chuyến đi (IN_PROGRESS -> COMPLETED).
4. 🛠️ Báo Cáo Bảo Trì & Xe
GET /vehicles/{id}: Xem thông tin xe tài xế đang lái.
POST /maintenance-records: Tài xế / Kỹ thuật gửi yêu cầu/nhật ký bảo trì xe.
