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
