# Alert Module

## Purpose
Turn critical log events into deduplicated, rule-driven Incidents and notifications.

## Input
- Messages from `alert.queue` routed from `critical.exchange`.
- Alert rules from PostgreSQL, with Redis caching.
- Existing Incident state from PostgreSQL.

Critical event contains at least:
- event_id
- application_id
- environment_id
- level
- message
- timestamp
- trace_id
- fingerprint inputs

## Responsibility
1. Consume critical events.
2. Resolve the applicable Alert Rule for `application + environment + level`.
3. Generate/find an error fingerprint.
4. Use Redis atomic operations/Lua to track frequency and dedup state.
5. Evaluate threshold over the configured time window.
6. Create or update an Incident in PostgreSQL.
7. Ensure repeated matching errors update the existing Incident instead of creating new Incidents.
8. Transition Incident to `OPEN` when triggered.
9. Resolve Incident after `resolve_after_seconds` without matching events.
10. Publish Incident realtime events to the Realtime Module.
11. Create/update notification state and send Telegram notifications.
12. ACK the critical message only after the event has been safely accounted for.

## Output
- Incident created/updated/resolved in PostgreSQL.
- Realtime Incident events.
- Telegram notification attempts.
- Notification delivery state.

## Dependencies
- RabbitMQ
- Redis
- PostgreSQL
- Telegram Bot API
- Realtime Module

## Error handling
- Missing/disabled rule -> account for the event and continue without opening an Incident.
- PostgreSQL temporary failure -> do not ACK; retry.
- Redis unavailable -> do not bypass dedup by default; keep the critical message pending and retry.
- Telegram failure -> Incident state remains persisted; retry notification independently.
- Duplicate critical message -> must not create duplicate Incident state.

## Must NOT do
- Must not store the full high-volume log stream in PostgreSQL.
- Must not consume `raw.queue`.
- Must not perform UI rendering.
- Must not own user authentication.
- Must not make application authorization decisions.
- Must not silently discard a critical event.
