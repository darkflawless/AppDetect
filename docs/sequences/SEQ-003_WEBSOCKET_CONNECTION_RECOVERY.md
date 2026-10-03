# SEQ-003 — WebSocket Connection Recovery

## Trách nhiệm

Mô tả duy nhất lifecycle kết nối WebSocket của Android: xác thực, đăng ký gateway sở hữu socket, heartbeat, route command và reconnect sang gateway khác khi lỗi hoặc deploy.

| Thuộc tính | Nội dung |
|---|---|
| Trigger | Android bắt đầu connected fleet session hoặc phát hiện socket đã đóng |
| Precondition | Device có access token và `deviceId` hợp lệ |
| Success outcome | Registry trỏ tới gateway đang giữ socket; command tới đúng thiết bị |
| Failure outcome | App retry bằng exponential backoff + jitter; mapping cũ tự hết TTL |
| Architecture reference | [Large-scale System Architecture](../LARGE_SCALE_SYSTEM_DESIGN.md) |

## Sequence

```mermaid
sequenceDiagram
    autonumber
    participant APP as Android
    participant LB as Load Balancer
    participant GW1 as WebSocket Gateway A
    participant REG as Redis Connection Registry
    participant CMD as Command Service
    participant GW2 as WebSocket Gateway B

    APP->>LB: WSS connect + access token + deviceId
    LB->>GW1: Chọn gateway còn capacity
    GW1->>GW1: Validate token
    GW1->>REG: SET deviceId → GW1 with TTL
    GW1-->>APP: CONNECTED + heartbeat policy

    loop Heartbeat
        APP->>GW1: ping / health
        GW1->>REG: Refresh TTL
        GW1-->>APP: pong
    end

    CMD->>REG: Lookup current gateway
    REG-->>CMD: GW1
    CMD->>GW1: Route command
    GW1-->>APP: Deliver command

    Note over GW1: Gateway lỗi hoặc được drain khi deploy
    APP--xGW1: Connection closed
    APP->>APP: Exponential backoff + random jitter
    APP->>LB: Reconnect
    LB->>GW2: Chọn gateway khác
    GW2->>REG: Replace deviceId → GW2 with TTL
    GW2-->>APP: CONNECTED
```

## Ràng buộc riêng của sequence

- Mỗi gateway chỉ giữ socket mà chính nó sở hữu.
- Redis Connection Registry không thay thế socket local; nó chỉ phục vụ discovery/routing.
- Mapping có TTL và được refresh bằng heartbeat.
- Client không reconnect theo chu kỳ cố định vì có thể tạo reconnect storm.
- Gateway phải graceful drain khi deploy: ngừng nhận kết nối mới trước khi đóng kết nối cũ.
