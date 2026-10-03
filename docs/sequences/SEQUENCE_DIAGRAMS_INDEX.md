# DriverGuard — Sequence Diagram Catalog

## Trách nhiệm tài liệu

File này chỉ là **mục lục và quy ước quản lý Sequence Diagram**. Nó không thay thế System Architecture, Use Case Model hoặc đặc tả API.

Tài liệu nền:

- [System Architecture](../LARGE_SCALE_SYSTEM_DESIGN.md)
- Use Case Model: quản lý trong một file UML riêng khi được thiết kế.

## Quy tắc SRP

- Một file sequence mô tả đúng một scenario/use case cụ thể.
- Luồng thành công và các nhánh lỗi gắn chặt với cùng scenario có thể ở chung một file.
- Nếu một nhánh lỗi có lifecycle, actor hoặc mục tiêu riêng, tách thành sequence khác.
- Mỗi sequence phải có ID ổn định, trigger, precondition, success outcome và failure outcome.
- Sequence chỉ tham chiếu tên component đã định nghĩa trong System Architecture; không tự tạo kiến trúc mới.
- Thay đổi cấu trúc service/store sửa ở System Architecture trước, sau đó mới cập nhật các sequence bị ảnh hưởng.

## Danh mục hiện tại

| ID | Sequence | Mục tiêu | Trạng thái |
|---|---|---|---|
| SEQ-001 | [GPS Telemetry Ingestion](SEQ-001_GPS_TELEMETRY_INGESTION.md) | Nhận, buffer, xử lý và phân phối vị trí xe | Draft |
| SEQ-002 | [Critical Safety Alert & HITL](SEQ-002_CRITICAL_ALERT_HITL.md) | Cảnh báo local, tạo HITL case và gửi lệnh đã duyệt | Draft |
| SEQ-003 | [WebSocket Connection Recovery](SEQ-003_WEBSOCKET_CONNECTION_RECOVERY.md) | Kết nối, route command và phục hồi khi gateway lỗi | Draft |

## Nhóm ID dành cho mở rộng

| Khoảng ID | Nhóm use case |
|---|---|
| SEQ-001–099 | Telemetry, device connectivity và trip lifecycle |
| SEQ-100–199 | DMS detection, local alert và safety escalation |
| SEQ-200–299 | Fleet manager, HITL và command workflow |
| SEQ-300–399 | Evidence, media và privacy |
| SEQ-400–499 | Authentication, authorization và device provisioning |
| SEQ-500–599 | Failure recovery, retry, DR và operational flows |

Không tạo sẵn hàng chục file rỗng. Chỉ thêm sequence khi use case/scenario tương ứng đã được xác định.
