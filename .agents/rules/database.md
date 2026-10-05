# Persistence Guardrails

- Inspect the applicable SQL contract before changing persistence code.
- Do not invent tables or columns without explicit approval; update the authoritative schema contract before implementing a schema change.
- Keep relational/configuration/Incident state in PostgreSQL and searchable logs/analytics in ClickHouse.
- Redis is operational cache/dedup state, not an authoritative business datastore.
- Respect module data ownership; do not directly modify another module's persistence.
- Do not use distributed transactions between PostgreSQL and ClickHouse.
- Preserve idempotency requirements for at-least-once delivery and stable event identity.
- Do not treat ClickHouse background deduplication as immediate strict uniqueness.
- Follow documented retention and deletion policies.
- Apply PostgreSQL schema changes through Flyway migrations; do not use Hibernate auto-DDL for schema mutation.

## Sources

- `docs/contracts/postgres-schema.sql`
- `docs/contracts/clickhouse-schema.sql`
- `docs/contracts/schema-notes.md`
- `docs/modules/conventions.md`
- `docs/architecture/failure-handling.md`
