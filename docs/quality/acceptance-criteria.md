# Acceptance Criteria

These criteria define when the MVP is functionally complete.

## AC01 — Application Access

Given an Engineer is assigned to Application A, the Engineer can access logs/incidents/analytics for all environments of A and cannot access Application B.

Human APIs require authenticated users; only Admin can manage applications/environments, access assignments, API keys, and alert rules.

## AC02 — API Key Scope

Given an API key belongs to Environment `DEV` of Application A, requests using that key for another application/environment are rejected. Creation/rotation returns the secret once; revoked/replaced keys stop authenticating after invalidation or cache expiry, with bounded local caching.

## AC03 — Successful Ingestion

Given a valid API key and valid JSON batch, when logs are submitted, RabbitMQ confirms publication and the API returns `202` using the common response envelope.

The request does not wait for ClickHouse.

## AC04 — Invalid Ingestion

Malformed JSON, missing required request fields, invalid API key, or invalid application/environment binding is rejected and is not published as a valid raw event.

## AC05 — Processing and Persistence

Valid raw events are normalized and stored in ClickHouse. Processing flushes when either:
- batch reaches 1,000 logs; or
- 2 seconds have elapsed.

## AC06 — At-Least-Once Safety

If a message is redelivered, the same `event_id` is preserved. Redelivery must not create duplicate Incident state or duplicate notification storms. For Alert, verify both Redis-success/PostgreSQL-rollback and PostgreSQL-commit/before-ACK crashes against [recovery accounting](../architecture/failure-handling.md#alert-accounting-and-redelivery); no Incident update is lost and no event is counted twice.

## AC07 — Invalid Processed Data

A permanently unprocessable raw message is routed to the DLQ and is not treated as an application incident.

## AC08 — Log Search

Authorized users can search by:
- application;
- environment;
- level;
- time range;
- trace_id;
- message.

Results use cursor pagination and never expose unauthorized applications.

## AC09 — Realtime Viewer

Authorized users receive normalized logs and Incident lifecycle events through SSE without page reload. Application/environment/level filters work server-side and no cross-application data leak occurs. Reconnection works, and bounded buffering/rendering keeps slow clients usable.

## AC10 — Incident Detection

Given a configured rule for application/environment/level, when matching ERROR/CRITICAL events reach its threshold inside the configured window, an Incident is opened.

## AC11 — Alert Deduplication

Repeated matching errors update the same Incident according to [Alert semantics](../modules/04-alert.md#deterministic-semantics). Verify level-separated fingerprints, the sliding-window boundary, opening count including pre-threshold events, one increment per distinct subsequent event, cooldown without a new Incident, and a fresh threshold after resolution.

## AC12 — Notification Failure

If Telegram delivery fails, the Incident remains persisted and visible; notification failure can be retried independently.

## AC13 — Dependency Failure

- RabbitMQ publish failure -> ingestion does not return `202`.
- ClickHouse transient failure -> Processing retries and does not silently ACK/drop the raw event.
- Redis failure in Alert -> do not bypass dedup and flood notifications.
- Realtime failure -> ingestion/storage continue.

## AC14 — Retention

Logs older than 7 days are removed from ClickHouse regardless of log level. PostgreSQL configuration and Incident data are not deleted by the log-retention job.

## AC15 — Required Demo

Given the demo log generator sends **500 logs within 2 seconds**:

- ingestion accepts valid traffic without application errors;
- logs are eventually present in ClickHouse;
- Live View displays incoming logs smoothly;
- ERROR/CRITICAL logs follow the alert path;
- no silent loss is observed in the demonstrated flow.

## AC16 — Incident Resolution

Given an OPEN Incident, when no matching error is observed for the configured `resolve_after` duration, the Incident transitions to `RESOLVED`.

## AC17 — Health Analytics and Performance Validation

Authorized users receive hourly total/ERROR/CRITICAL counts and Log Error Rate as defined in FR08. Validate sustained throughput and healthy-condition latency against NFR01–04 design targets, separately from the mandatory AC15 demo.
