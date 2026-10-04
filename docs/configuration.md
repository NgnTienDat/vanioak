# Configuration Specification

All environment-dependent values must be configurable. Do not hard-code credentials, hosts or operational thresholds.

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
| `INGESTION_MAX_BATCH_SIZE` | Maximum logs accepted per HTTP batch |
| `INGESTION_MAX_REQUEST_BYTES` | Request-size protection |
| `API_KEY_LOCAL_CACHE_TTL` | Local API-key cache TTL |
| `API_KEY_LOCAL_CACHE_MAX_SIZE` | Prevent unbounded RAM usage |

Exact ingestion limits should be validated by load testing rather than guessed in code.

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
TELEGRAM_ENABLED=true|false
```

## 7. Security

Configure the chosen human-authentication mechanism without committing secrets.

Examples:

```text
JWT_SECRET=<secret>
JWT_ACCESS_TOKEN_TTL=<duration>
CORS_ALLOWED_ORIGINS=<origins>
```

## 8. Environment Files

Repository may contain:

```text
.env.example
```

It must contain variable names and safe placeholder values only. Real `.env` files and credentials must be ignored by Git.
