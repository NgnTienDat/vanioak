# Persistence Guardrails

- Inspect the applicable SQL contract before changing persistence code.
- Do not invent tables or columns without explicit approval; update the authoritative schema contract before implementing a schema change.
- Keep relational/configuration/Incident state in PostgreSQL and searchable logs/analytics in ClickHouse.
- Redis holds operational cache/dedup state and authoritative access-token revocation by JWT `jti` until expiry. Refresh-session metadata belongs to PostgreSQL; blacklist checks must never be bypassed or treated as optional cache lookups.
- Respect module data ownership; do not directly modify another module's persistence.
- Do not use distributed transactions between PostgreSQL and ClickHouse.
- Preserve idempotency requirements for at-least-once delivery and stable event identity.
- Do not treat ClickHouse background deduplication as immediate strict uniqueness.
- Follow documented retention and deletion policies.
- Apply PostgreSQL schema changes through Flyway migrations; do not use Hibernate auto-DDL for schema mutation.
- Use Spring Data JPA by default for PostgreSQL-backed module persistence.
- Keep JPA entities and repositories inside the owning module's `internal/` package; never expose them through module public APIs or HTTP contracts.
- Use lower-level JDBC only when explicitly justified by the implementation plan and approved.

## Sources

- `docs/contracts/postgres-schema.sql`
- `docs/contracts/clickhouse-schema.sql`
- `docs/contracts/schema-notes.md`
- `docs/modules/conventions.md`
- `docs/architecture/failure-handling.md`
