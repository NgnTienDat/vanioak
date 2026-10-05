# RabbitMQ Event Contracts v1

## Common envelope

All RabbitMQ domain events use:

```json
{
  "event_id": "0f5f3e9d-...",
  "event_type": "processed.log",
  "occurred_at": "2026-10-02T08:01:22.150Z",
  "producer": "processing",
  "schema_version": 1,
  "payload": {}
}
```

For single-log lifecycle events, `event_id` is the stable logical log identifier and is reused across retries/redeliveries. For a raw batch message, the envelope identifies the batch/message while each log in `payload.logs` carries its own stable `event_id`.

## 1. Raw log batch

Routing:

```text
raw.exchange -- raw.log --> raw.queue
```

Single-log ingestion may be normalized to the same structure with one item in `logs`.

Payload:

```json
{
  "request_id": "2bf6e2c8-...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "received_at": "2026-10-02T08:01:22.150Z",
  "logs": [
    {
      "event_id": "e6...",
      "host_ip": "10.10.20.15",
      "level": "ERROR",
      "message": "Database connection timeout",
      "timestamp": "2026-10-02T08:01:22.123Z",
      "trace_id": "abc-123",
      "metadata": {}
    }
  ]
}
```

Identity supplies the authenticated application/environment UUIDs. Processing copies these IDs and the batch's ingestion-time `received_at` to each stored log; retries preserve them. The envelope `event_type` is `raw.log`.

## 2. Processed log event

Routing:

```text
processed.exchange -- processed.log --> realtime.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "host_ip": "10.10.20.15",
  "level": "ERROR",
  "message": "Database connection timeout",
  "timestamp": "2026-10-02T08:01:22.123Z",
  "trace_id": "abc-123",
  "metadata": {},
  "received_at": "2026-10-02T08:01:22.150Z",
  "processed_at": "2026-10-02T08:01:22.310Z"
}
```

The envelope `event_type` is `processed.log`.

## 3. Critical event

Routing:

```text
critical.exchange -- critical.log --> alert.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "level": "ERROR",
  "message": "Database connection timeout",
  "timestamp": "2026-10-02T08:01:22.123Z",
  "trace_id": "abc-123",
  "received_at": "2026-10-02T08:01:22.150Z"
}
```

Processing publishes this event only for `ERROR` and `CRITICAL`, with envelope `event_type: critical.log`. `received_at` is required and copied unchanged from the raw batch, including on retries; its use is defined in [Alert Module](../modules/04-alert.md#deterministic-semantics). The Alert Module computes the incident fingerprint from the critical event; Processing does not own fingerprint generation.

## 4. Invalid log / DLQ event

Routing:

```text
dead-letter.exchange -- dead-letter.log --> dead-letter.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "reason": "MISSING_REQUIRED_FIELD",
  "field": "timestamp",
  "original_message": {},
  "failed_at": "2026-10-02T08:01:22.200Z"
}
```

Permanent/data errors go to DLQ with envelope `event_type: dead-letter.log`. Transient infrastructure errors use the retry path first.

## 5. Incident event for Realtime

Alert emits an internal event to Realtime using the common envelope:

```json
{
  "event_id": "i1-event...",
  "event_type": "incident.created",
  "occurred_at": "2026-10-02T08:01:25Z",
  "producer": "alert",
  "schema_version": 1,
  "payload": {
    "incident_id": "i1...",
    "application_id": "a1...",
    "environment_id": "e3...",
    "level": "ERROR",
    "status": "OPEN",
    "message": "Database connection timeout",
    "error_count": 10
  }
}
```

Lifecycle event types:

All lifecycle payloads carry `incident_id`, `application_id`, `environment_id`, and `level` for authorization/filtering. Realtime may project these into the SSE payloads defined in `sse-api.md`.

- `incident.created`: `status: OPEN`, `message`, `error_count` (shown above).
- `incident.updated`: `status: OPEN`, `error_count`, `last_seen_at`.
- `incident.resolved`: `status: RESOLVED`, `resolved_at`.
