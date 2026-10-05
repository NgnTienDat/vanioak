# Ingestion Module

## Purpose
Provide a high-throughput HTTP endpoint for applications to submit JSON logs and durably enqueue raw log data.

## Input
HTTP:
- `POST /api/v1/logs`
- `POST /api/v1/logs/batch`
- API key in request header.
- Single/batch JSON log payloads defined in [OpenAPI](../contracts/openapi.yaml).

## Responsibility
1. Validate request structure and payload size.
2. Verify API key:
   - check bounded local RAM cache with TTL first; valid hits do not query Redis/PostgreSQL;
   - on cache miss, call the Identity Module internal service;
   - Identity may resolve from Redis/PostgreSQL.
3. Verify the API key is authorized for the submitted application/environment, including the active-state/cache rules in [Security](../quality/security.md#disabled-state). An ACTIVE key alone is insufficient.
4. Attach/retain ingestion metadata needed downstream.
5. Publish raw log records/messages to `raw.exchange`.
6. Use RabbitMQ publisher confirms.
7. Return `202 Accepted` only after the message is accepted by RabbitMQ.

## Output
- Raw events for Processing as defined in [Event Contracts](../contracts/event-contracts.md).
- HTTP acceptance/error responses defined in [OpenAPI](../contracts/openapi.yaml), with examples in [REST API](../contracts/rest-api.md).

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
