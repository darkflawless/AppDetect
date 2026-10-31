# Hướng dẫn cấu hình và chạy ứng dụng AppDetect

Tài liệu này hướng dẫn cách cấu hình địa chỉ IP máy tính và các cổng dịch vụ (REST API, WebSocket, MQTT, UDP, Media MinIO) để điện thoại kết nối thông suốt với hệ thống Backend chạy trên máy tính.

---

## 📌 Nguyên tắc hoạt động
* Điện thoại và máy tính chạy server **bắt buộc phải kết nối chung một mạng Wi-Fi** (hoặc chung mạng LAN/Hotspot).
* Điện thoại **không bật 4G/5G** khi đang kết nối server nội bộ qua Wi-Fi.
* Toàn bộ cấu hình IP và Port đã được **quản lý tập trung 100% tại file `gradle.properties`**, không cần sửa tay bất kỳ dòng code Java/XML nào.

---

## Bước 1: Lấy địa chỉ IPv4 máy tính của bạn
1. Trên máy tính Windows, mở terminal PowerShell hoặc Command Prompt (`Win + R` -> gõ `cmd` -> Enter).
2. Chạy lệnh:
   ```cmd
   ipconfig
   ```
3. Tìm phần card mạng Wi-Fi (hoặc Ethernet nếu cắm dây LAN), ghi lại dòng **IPv4 Address** (Ví dụ: `192.168.1.11`).

---

## Bước 2: Cập nhật cấu hình tập trung trong `gradle.properties`
Mở file **`gradle.properties`** ở thư mục gốc của project Android, tìm đến phần:

```properties
# ==============================================================================
# CẤU HÌNH KẾT NỐI SERVER TẬP TRUNG (SERVER IP & PORTS CONFIGURATION)
# Khi thay đổi địa chỉ IP máy tính (mạng Wi-Fi mới) hoặc Port, chỉ cần sửa tại đây!
# Sau khi sửa, chạy lại app hoặc Sync Gradle để cập nhật vào BuildConfig.
# ==============================================================================

# Địa chỉ IPv4 của máy tính chạy Server trong mạng LAN (xem bằng lệnh 'ipconfig' trên Windows)
server.ip=192.168.1.11

# Cổng REST API của Backend Spring Boot (phục vụ đăng nhập, lấy hồ sơ /drivers/me, lịch trình, ca chạy, vi phạm)
server.rest_port=8080

# Cổng WebSocket Realtime (phục vụ nhận lệnh START/STOP stream và bắn sự kiện cảnh báo khẩn cấp lên Web Admin)
server.ws_port=8080

# Cổng MQTT Broker (phục vụ gửi tọa độ GPS định kỳ, telemetry xe theo chu kỳ 1s)
server.mqtt_port=1883

# Cổng UDP Stream Video (phục vụ truyền luồng video khung hình tài xế trực tiếp với độ trễ thấp)
server.udp_port=9090

# Cổng Media Server / MinIO Object Storage (phục vụ lưu trữ và tải ảnh avatar tài xế, ảnh vi phạm)
server.media_port=9000
```

👉 **Bạn chỉ cần thay giá trị `server.ip`** thành địa chỉ IPv4 vừa tìm được ở Bước 1. Các cổng dịch vụ (`rest_port`, `ws_port`, `media_port`...) có thể điều chỉnh tùy theo môi trường triển khai thực tế.

---

## Bước 3: Build và cài đặt ứng dụng
Sau khi sửa `gradle.properties`, bạn có thể chạy:

* **Qua Android Studio**: Nhấn nút **Sync Project with Gradle Files** 🐘 -> Nhấn nút **Run 'app'** ▶️.
* **Hoặc qua dòng lệnh (Terminal)**:
  ```powershell
  ./gradlew installDebug
  ```

Gradle sẽ tự động inject các thông số cấu hình này vào `BuildConfig` và toàn bộ các module trong ứng dụng (`RetrofitClient`, `AppWebSocketClient`, `Glide`, `AppMqttClient`, `UdpFrameSender`) sẽ tự động cập nhật ngay lập tức.

---

## Bước 4: Kiểm tra kết nối từ điện thoại
1. **Kiểm tra REST API Backend**: Mở trình duyệt Chrome trên điện thoại, truy cập:
   ```text
   http://<IP_MAY_TINH>:8080/swagger-ui.html
   ```
   *(Ví dụ: `http://192.168.1.11:8080/swagger-ui.html`)*  
   Nếu trang Swagger của Backend hiển thị thành công, kết nối mạng giữa điện thoại và máy tính đã thông suốt.

2. **Nếu điện thoại không kết nối được**:
   * Kiểm tra **Windows Defender Firewall**: Tạm thời tắt hoặc mở quyền Inbound Rule cho cổng `8080`, `9000`, `1883`, `9090`.
   * Đảm bảo kiểu mạng Wi-Fi trên Windows đang là **Private Network** (thay vì Public Network).
   * Đảm bảo Backend Spring Boot đã được khởi động (`mvn spring-boot:run`).
