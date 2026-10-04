# User Journeys, Use Cases & Edge Cases

## 1. User Journeys

### UJ01 — Application gửi log

```text
Application
  -> POST /logs
  -> API Key validation
  -> JSON validation
  -> Message Queue
  -> 202 Accepted
  -> Log Worker
  -> Database
```

Producer không cần chờ Database xử lý.

---

### UJ02 — Engineer tra cứu log

```text
Engineer Login
  -> Chọn Application
  -> Chọn Environment
  -> Chọn Level / Time Range / Trace ID / Message
  -> Search
  -> Cursor Pagination
  -> Xem kết quả
```

---

### UJ03 — Engineer điều tra Incident

```text
Error tăng cao
  -> Alert Rule triggered
  -> Incident OPEN
  -> Telegram + SSE
  -> Engineer mở Incident
  -> Xem error logs
  -> Tra cứu Trace ID
  -> Điều tra các log liên quan
```

---

### UJ04 — Admin quản lý quyền và alert rule

```text
Admin Login
  -> Create/Update Application
  -> Assign Engineer
  -> Configure Application + Environment + Level alert rule
```

---

## 2. Main Use Cases

| ID | Use Case | Actor |
|---|---|---|
| UC01 | Login | Engineer/Admin |
| UC02 | Create/Update Application | Admin |
| UC03 | Assign Engineer to Application | Admin |
| UC04 | Send Single Log | Application |
| UC05 | Send Batch Logs | Application |
| UC06 | Search Logs | Engineer/Admin |
| UC07 | View Live Logs | Engineer/Admin |
| UC08 | View Incident | Engineer/Admin |
| UC09 | Create Alert Rule | Admin |
| UC10 | Update Alert Rule | Admin |
| UC11 | Receive Alert | Engineer/Admin |
| UC12 | Automatic Log Cleanup | System |
| UC13 | View Health Analytics | Engineer/Admin |

---

## 3. Edge Cases

| Case | Expected Behavior |
|---|---|
| JSON invalid | Reject request |
| Missing application | Reject request |
| Unknown application | Reject request |
| Invalid environment | Reject request |
| Invalid API Key | 401/403 |
| API Key cache miss | Fallback to Identity module/Redis |
| Redis/API Key store unavailable | Do not accept unverified credential; return service/auth error |
| MQ unavailable | 503 |
| MQ full | 429/503 + producer retry |
| DB unavailable | Worker retries; message is not ACKed as successfully processed |
| Worker crash before ACK | Message may be redelivered |
| Duplicate message | Accept because delivery is at-least-once |
| Invalid message that cannot be processed | Retry then DLQ |
| Redis unavailable during incident processing | Retry critical event; do not bypass dedup |
| Telegram unavailable | Retry notification; incident remains stored |
| SSE disconnected | Client reconnects |
| Engineer requests unauthorized application | 403 |
| Engineer requests unauthorized environment | 403 |
| 1,000 logs/s sustained | Must meet target throughput |
| Burst exceeds processing rate | MQ buffers burst and system applies backpressure if capacity is exhausted |
| Same error repeats 1000 times | One Incident; increase `error_count` |
| Error stops | Incident transitions to RESOLVED after configured duration |
| Log older than 7 days | Background cleanup deletes it |
| Browser receives too many logs | Server/client buffers or batches updates to protect UI |
