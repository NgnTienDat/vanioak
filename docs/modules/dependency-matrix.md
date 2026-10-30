# Module Dependency Matrix

| Module | PostgreSQL | ClickHouse | Redis | RabbitMQ | Identity | Telegram |
|---|---|---|---|---|---|---|
| Identity | R/W | - | R/W | - | self | - |
| Ingestion | - | - | local/indirect | Publish | Call | - |
| Processing | - | Write | - | Consume/Publish | - | - |
| Alert | R/W | - | R/W | Consume | Environment metadata; delegated manual-resolution authorization | Send |
| Realtime | - | - | - | Consume | Authorization | - |
| Analysis | - | Read | optional | - | - | - |
| Retention | - | Read TTL metadata; native TTL deletes | - | - | - | - |

## Identity storage responsibilities

- PostgreSQL: users and existing Identity relational data, plus authoritative refresh-token/session metadata for rotation and family revocation.
- Redis: authoritative access-token blacklist by JWT `jti`, expiring at the access token's `exp`; no raw JWT storage and no bypass when Redis is unavailable.
- Identity's API-key cache is a separate Redis use case with its existing safe PostgreSQL fallback. Alert cache/frequency/dedup/cooldown state is unchanged. Those cache/recovery policies do not apply to the auth blacklist.

## Dependency rules
- Alert may resolve an environment's parent application through Identity's public
  EnvironmentScopeFacade for committed Incident realtime events. This trusted
  in-process metadata lookup does not grant human access; Realtime retains all
  subscription and per-send authorization. Alert must not access Identity tables
  or internal repositories directly.
- Ingestion must not depend on ClickHouse availability.
- Processing must not depend on Backend API availability.
- Alert must not depend on Realtime availability for Incident persistence.
- Realtime must not be required for data durability.
- Analysis must not be in the ingestion critical path.
- Retention must not block normal log processing.

- Manual Incident resolution delegates current actor/assignment/scope checks through public ApplicationAccessFacade and EnvironmentScopeFacade after the Alert scope lock. Disabled scopes may be closed by an otherwise authorized active actor. No Identity internal/repository access is permitted.
