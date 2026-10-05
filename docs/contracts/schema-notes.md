# Database Schema Notes

## Responsibility split

Data ownership is defined in [Architecture](../architecture/architecture.md); exact structures are in [PostgreSQL Schema](postgres-schema.sql) and [ClickHouse Schema](clickhouse-schema.sql).

## Environment

`environment` is an explicit first-class field. It is not inferred from `host_ip`.

An ingestion credential is scoped to an environment:

`API key -> environment -> application`

`host_ip` is stored on the log event as source metadata.

## At-least-once and event identity

Event identity follows [Module Conventions](../modules/conventions.md) and [Event Contracts](event-contracts.md).

ClickHouse uses `ReplacingMergeTree(processed_at)` so duplicate physical rows for the same sort key can be collapsed during background merges. This does not change the system's delivery guarantee: the platform remains at-least-once.

## Alert incident identity

`environment_id` identifies both the environment and, through the `environments` table, its application. Rule and OPEN-Incident uniqueness constraints are defined in [PostgreSQL Schema](postgres-schema.sql); fingerprint generation belongs to [Alert Module](../modules/04-alert.md).

## Deletion policy

Applications and environments should normally be disabled rather than physically deleted, because historical ClickHouse logs may still refer to their IDs. PostgreSQL foreign keys intentionally avoid cascading deletion of applications.
