# Configuration Specification

All environment-dependent values must be configurable. Do not hard-code credentials, hosts or operational thresholds.

MVP configuration must preserve the 1,000-log/2-second processing flush and 7-day retention; `LOG_RETENTION_DAYS` must match the ClickHouse TTL.

## 1. Core Configuration

| Key | Default / Example | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | Backend port |
| `POSTGRES_URL` | required | PostgreSQL connection |
| `POSTGRES_USERNAME` | required | PostgreSQL user |
| `POSTGRES_PASSWORD` | secret | PostgreSQL password |
| `CLICKHOUSE_URL` | required | ClickHouse connection |
| `CLICKHOUSE_USERNAME` | required | ClickHouse user |
| `CLICKHOUSE_PASSWORD` | secret | ClickHouse password |
| `REDIS_HOST` | `redis` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RABBITMQ_HOST` | `rabbitmq` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `RABBITMQ_USERNAME` | required | RabbitMQ user |
| `RABBITMQ_PASSWORD` | secret | RabbitMQ password |

## 2. Processing

| Key | Default |
|---|---:|
| `PROCESSING_BATCH_SIZE` | `1000` |
| `PROCESSING_BATCH_TIMEOUT_MS` | `2000` |
| `PROCESSING_RETRY_MAX_ATTEMPTS` | `3` |

Suggested retry delays: `5s`, `30s`, `120s`. Keep them configurable.

## 3. Ingestion

| Key | Purpose |
|---|---|
| `INGESTION_MAX_BATCH_SIZE` | 1,000 logs per HTTP batch, matching the API contract |
| `INGESTION_MAX_REQUEST_BYTES` | Request-size protection |
| `API_KEY_LOCAL_CACHE_TTL` | Local API-key cache TTL |
| `API_KEY_LOCAL_CACHE_MAX_SIZE` | Prevent unbounded RAM usage |

Validate request-byte and cache limits by load testing without changing the MVP batch limit.

## 4. Retention

```text
LOG_RETENTION_DAYS=7
```

Applies to all log levels and all dev/test/staging environments.

## 5. Realtime

Configure:
- SSE heartbeat interval;
- bounded per-client buffer;
- optional server-side batch/throttle interval;
- allowed frontend origins.

No unbounded SSE client buffer is permitted.

## 6. Alerting

Alert threshold/window/cooldown/resolve-after values are business configuration stored in PostgreSQL per application/environment/level, not environment variables.

Infrastructure configuration includes:

```text
TELEGRAM_BOT_TOKEN=<secret>
TELEGRAM_CHAT_ID=<deployment-chat-id>
TELEGRAM_ENABLED=true|false
```

MVP uses one configured Telegram destination for the deployment, not per-user/application routing. When `TELEGRAM_ENABLED=true`, bot token and chat ID are required; invalid/missing configuration fails startup. Disabled delivery leaves Incident processing enabled. Alert retry uses `ALERT_RETRY_MAX_ATTEMPTS=3` (retries after the initial attempt) and configurable delays, following [Topology](../architecture/rabbitmq-topology.md#5-retry-and-dlq).

## 7. Security

Configure the chosen human-authentication mechanism without committing secrets.

Examples:

```text
JWT_SECRET=<secret>
JWT_ACCESS_TOKEN_TTL=<duration>
CORS_ALLOWED_ORIGINS=<origins>
```

### Initial Admin bootstrap

```text
BOOTSTRAP_ADMIN_ENABLED=false
BOOTSTRAP_ADMIN_USERNAME=<initial-admin-username>
BOOTSTRAP_ADMIN_PASSWORD=<secret>
```

For a fresh deployment, explicitly enable bootstrap and supply a username (maximum 100 characters) and a non-empty password through configuration. After schema initialization, Identity creates one ACTIVE ADMIN only when `users` is empty, hashes the password using the normal password policy, and commits the creation before serving login requests. Missing/invalid bootstrap credentials on an empty database fail startup when bootstrap is enabled; a failed database operation must not be reported as successful bootstrap.

Serialize the empty-database check and insertion so concurrent startup cannot create multiple initial users. Repeated startup against a non-empty `users` table is a no-op: never overwrite passwords, promote users, or re-enable disabled users. Remove the bootstrap password from runtime configuration and disable bootstrap after creation. This is an initial Admin path only, not a new public user-management API.

## 8. Environment Files

Repository may contain:

```text
.env.example
```

It must contain variable names and safe placeholder values only. Real `.env` files and credentials must be ignored by Git.
