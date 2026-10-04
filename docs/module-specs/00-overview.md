# Module Specification

## Architecture
The project uses a **Modular Monolith** architecture implemented with Java + Spring Boot.

The modules below are logical modules/services inside the same application codebase/process boundary where appropriate. They are **not microservices**.

Modules:
- Identity
- Ingestion
- Processing
- Alert
- Realtime
- Analysis
- Retention

## Core infrastructure
- PostgreSQL: users, applications, environments, permissions, credentials metadata, alert rules, incidents, notification state.
- ClickHouse: high-volume normalized logs and analytics queries.
- RabbitMQ: asynchronous buffering and event delivery.
- Redis: API-key cache, alert-rule cache, incident deduplication/state.
- Frontend: React + TypeScript.
- Realtime protocol: SSE.

## RabbitMQ topology
- `raw.exchange` -> `raw.queue`
- `processed.exchange` -> `realtime.queue`
- `critical.exchange` -> `alert.queue` 
- `dead-letter.exchange` -> `dead-letter.queue`
- Retry path for transient processing failures: `raw.retry.queue` -> `raw.queue`

## Global invariants
1. Ingestion does not write logs directly to ClickHouse.
2. A raw message is ACKed only after successful processing/persistence according to the Processing contract.
3. Delivery semantics are **at-least-once**. Duplicate delivery is possible and must be handled safely.
4. ClickHouse is the source of truth for application log records. 
5. PostgreSQL is the source of truth for relational/configuration/state data.
6. Invalid/permanently unprocessable messages go to DLQ; transient dependency failures are retried.
7. Engineer authorization is application-scoped; access to an application includes its environments.
8. REST responses use the common envelope: `{"success": true|false, "message": "...", "data": ...}`.
9. Realtime delivery is best-effort. A disconnected client must be able to recover through normal REST search.
10. No module may silently drop a log or event.
