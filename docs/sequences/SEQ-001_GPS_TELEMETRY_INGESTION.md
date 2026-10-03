# SEQ-001 — GPS Telemetry Ingestion

## Trách nhiệm

Mô tả duy nhất luồng nhận GPS từ Android, đưa qua event backbone, cập nhật trạng thái mới nhất, lưu lịch sử và gửi delta có chọn lọc tới dashboard.

| Thuộc tính | Nội dung |
|---|---|
| Trigger | Mobile tạo GPS telemetry trong một trip đang hoạt động |
| Precondition | Device đã xác thực; local outbox hoạt động |
| Success outcome | GPS được ACK ở ingestion, xử lý bất đồng bộ và xuất hiện ở đúng fleet dashboard |
| Failure outcome | Event còn trong local outbox hoặc Kafka để retry; cảnh báo DMS local không bị ảnh hưởng |
| Architecture reference | [Large-scale System Architecture](../LARGE_SCALE_SYSTEM_DESIGN.md) |

## Sequence

```mermaid
sequenceDiagram
    autonumber
    participant APP as Android + Local Outbox
    participant API as Telemetry Ingestion
    participant BUS as Kafka gps.telemetry
    participant WORKER as GPS Processor
    participant REDIS as Redis Latest State
    participant TS as GPS History Store
    participant RT as Dashboard Realtime Gateway
    participant UI as Fleet Dashboard

    APP->>API: POST GPS batch + eventId/token
    API->>API: Auth + validate + rate limit
    API->>BUS: Enqueue, key = vehicleId
    API-->>APP: 202 Accepted
    BUS-->>WORKER: Consume ordered partition
    WORKER->>WORKER: Deduplicate + normalize + coalesce
    par Hot path
        WORKER->>REDIS: Update latest vehicle state
    and History path
        WORKER->>TS: Batch insert partitioned rows
    end
    WORKER-->>RT: Publish sampled position delta
    RT->>RT: Filter by fleet/visible vehicles
    RT-->>UI: One compact delta batch
```

## Ràng buộc riêng của sequence

- 10.000 xe gửi mỗi 5 giây tương đương khoảng 2.000 GPS event/giây và 172,8 triệu event/ngày nếu chạy liên tục.
- App gửi batch khi phục hồi mạng; không tạo hàng nghìn REST request nối tiếp.
- Xe đứng yên có thể giảm tần suất gửi.
- Dashboard đọc vị trí hiện tại từ Redis; lịch sử mới đọc GPS Store.
- GPS delta cũ có thể coalesce theo xe, nhưng không áp dụng quy tắc này cho critical safety alert.
