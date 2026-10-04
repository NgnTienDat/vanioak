# Database Schema Notes

## Responsibility split

- PostgreSQL: users, applications, environments, access control, ingestion credentials, alert rules, incidents and notification state.
- ClickHouse: application logs and analytical queries.

## Environment

`environment` is an explicit first-class field. It is not inferred from `host_ip`.

An ingestion credential is scoped to an environment:

`API key -> environment -> application`

`host_ip` is stored on the log event as source metadata.

## At-least-once and event identity

Every logical log event carries a stable `event_id`. If RabbitMQ redelivers the same message, the Processing Module must reuse the same `event_id` rather than generating a new one.

ClickHouse uses `ReplacingMergeTree(processed_at)` so duplicate physical rows for the same sort key can be collapsed during background merges. This does not change the system's delivery guarantee: the platform remains at-least-once.

## Alert incident identity

`environment_id` identifies both the environment and, through the `environments` table, its application. Alert rules are unique by `environment + level` and persist threshold, window, cooldown, and resolve-after settings. Incidents are unique for an `environment + fingerprint` while OPEN.

`fingerprint` represents the normalized application error pattern. `trace_id` is intentionally not part of the incident fingerprint because different requests can have different trace IDs while representing the same underlying error.

The partial unique index ensures at most one `OPEN` incident exists for the same `environment + fingerprint` (and therefore the same application).

## Retention

All logs are retained for 7 days regardless of level. ClickHouse TTL is used for automatic cleanup.

## Deletion policy

Applications and environments should normally be disabled rather than physically deleted, because historical ClickHouse logs may still refer to their IDs. PostgreSQL foreign keys intentionally avoid cascading deletion of applications.
