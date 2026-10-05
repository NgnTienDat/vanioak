# Alert Module

## Purpose
Turn critical log events into deduplicated, rule-driven Incidents and notifications.

## Input
- Messages from `alert.queue` routed from `critical.exchange`.
- Alert rules from PostgreSQL, with Redis caching.
- Existing Incident state from PostgreSQL.

The critical event shape is defined in [Event Contracts](../contracts/event-contracts.md).

## Responsibility
1. Consume critical events.
2. Resolve the applicable Alert Rule for `application + environment + level`.
3. Compute the fingerprint and evaluate the deterministic semantics below.
4. Use Redis atomic operations/Lua to track frequency and dedup state.
5. Evaluate threshold over the configured time window.
6. Create or update an Incident in PostgreSQL.
7. Ensure repeated matching errors update the existing Incident instead of creating new Incidents.
8. Transition Incident to `OPEN` when triggered.
9. Resolve Incident after `resolve_after_seconds` without matching events.
10. Publish Incident realtime events to the Realtime Module.
11. Create/update notification state and send Telegram notifications.
12. Account for each critical event under [Alert recovery](../architecture/failure-handling.md#alert-accounting-and-redelivery) before ACK; Redis membership alone is not proof of completed processing.

## Deterministic semantics

- Scope is `(environment_id, fingerprint)`; the environment determines the application. Fingerprint is lowercase hexadecimal SHA-256 of UTF-8 `level + "\n" + normalized_message`. Normalize the message by trimming and collapsing consecutive ASCII whitespace (space, tab, CR, LF, form feed, vertical tab) to one space; preserve case and all other characters. Do not include `trace_id`, `event_id`, or other per-request metadata. MVP does not parse/classify message text or strip identifiers embedded in it; producers should use stable message templates for grouping.
- Frequency uses server-assigned ingestion `received_at`, not producer `timestamp` or retry time. For a scope and its enabled rule, let `T` be the greatest `received_at` among previously accounted matching events and the current event. Count distinct `event_id` in the sliding interval `(T - window_seconds, T]`. Duplicate delivery never advances time or increases frequency. Late events outside that interval do not help reach the threshold; events accounted with a missing/disabled rule do not enter frequency tracking.
- With no OPEN Incident, reaching the threshold opens one with `error_count` equal to the distinct count in that window, `first_seen_at` equal to its earliest `received_at`, and `last_seen_at = T`. Associate those window events with the new Incident. While OPEN, each newly accounted matching event increments `error_count` once, even if frequency has fallen below threshold; `first_seen_at` takes the earliest matching `received_at` and `last_seen_at` never moves backwards. Events already included in the opening count are not added again on redelivery.
- The opening trigger creates a notification when Telegram is enabled. While OPEN, a new distinct matching event may create another notification only after `cooldown_seconds` since the most recent notification was created, and only if no earlier notification is PENDING or FAILED awaiting retry. Cooldown uses server time; retries reuse the existing notification rather than reserving another. A timer alone must not generate repeat notifications. Notification reservation is persisted with the Incident update; cooldown never opens a second Incident.
- Resolve when server time has reached `last_seen_at + resolve_after_seconds`, under the same scope serialization as event updates. A later threshold may open a new Incident; the new opening window excludes events already assigned to the previous Incident.

Telegram uses the single deployment destination defined in [Configuration](../operations/configuration.md#6-alerting). When disabled, Incident processing continues without creating Telegram notification work.

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
- PostgreSQL temporary failure -> do not ACK as successfully processed; use confirmed retry/DLQ accounting in [Topology](../architecture/rabbitmq-topology.md#5-retry-and-dlq).
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
