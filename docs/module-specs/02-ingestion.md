# Ingestion Module

## Purpose
Provide a high-throughput HTTP endpoint for applications to submit JSON logs and durably enqueue raw log data.

## Input
HTTP:
- `POST /api/v1/logs`
- `POST /api/v1/logs/batch`
- API key in request header.
- JSON log payload containing at least:
  - application
  - environment
  - host_ip
  - level
  - message
  - timestamp
  - trace_id
  - optional metadata

## Responsibility
1. Validate request structure and payload size.
2. Verify API key:
   - check bounded local RAM cache first;
   - on cache miss, call the Identity Module internal service;
   - Identity may resolve from Redis/PostgreSQL.
3. Verify the API key is authorized for the submitted application/environment.
4. Attach/retain ingestion metadata needed downstream.
5. Publish raw log records/messages to `raw.exchange`.
6. Use RabbitMQ publisher confirms.
7. Return `202 Accepted` only after the message is accepted by RabbitMQ.

## Output
Success:
```json
{
  "success": true,
  "message": "Logs accepted for processing",
  "data": {
    "accepted_count": 100
  }
}
```

Failure:
- `400`: invalid payload.
- `401/403`: invalid or unauthorized API key.
- `429/503`: RabbitMQ unavailable or queue/backpressure condition.

## Dependencies
- Identity Module
- RabbitMQ
- Local in-memory cache
- Spring Web / validation

## Error handling
- Invalid JSON/schema -> reject immediately; do not enqueue.
- Invalid application/environment binding -> reject.
- RabbitMQ publish failure -> do not return success; return retryable error.
- RabbitMQ full/backpressure -> return `429` or `503`; producer is expected to retry with backoff.
- Identity unavailable and local cache miss -> reject safely.
- Never acknowledge an ingestion request when the message was not confirmed by RabbitMQ.

## Must NOT do
- Must not insert logs into ClickHouse/PostgreSQL.
- Must not run alert rules.
- Must not perform deduplication.
- Must not send notifications.
- Must not perform analytics.
- Must not decide Incident state.
