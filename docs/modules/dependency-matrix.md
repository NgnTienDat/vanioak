# Module Dependency Matrix

| Module | PostgreSQL | ClickHouse | Redis | RabbitMQ | Identity | Telegram |
|---|---|---|---|---|---|---|
| Identity | R/W | - | R/W | - | self | - |
| Ingestion | - | - | local/indirect | Publish | Call | - |
| Processing | - | Write | - | Consume/Publish | - | - |
| Alert | R/W | - | R/W | Consume | - | Send |
| Realtime | - | - | - | Consume | Authorization | - |
| Analysis | - | Read | optional | - | - | - |
| Retention | - | Write/Delete | - | - | - | - |

## Identity storage responsibilities

- PostgreSQL: users and existing Identity relational data, plus authoritative refresh-token/session metadata for rotation and family revocation.
- Redis: authoritative access-token blacklist by JWT `jti`, expiring at the access token's `exp`; no raw JWT storage and no bypass when Redis is unavailable.
- Identity's API-key cache is a separate Redis use case with its existing safe PostgreSQL fallback. Alert cache/frequency/dedup/cooldown state is unchanged. Those cache/recovery policies do not apply to the auth blacklist.

## Dependency rules
- Ingestion must not depend on ClickHouse availability.
- Processing must not depend on Backend API availability.
- Alert must not depend on Realtime availability for Incident persistence.
- Realtime must not be required for data durability.
- Analysis must not be in the ingestion critical path.
- Retention must not block normal log processing.
