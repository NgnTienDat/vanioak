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

Configure access and refresh JWT signing independently without committing secrets. These are target Auth settings, not a claim that Auth is implemented.

```text
JWT_ACCESS_SECRET=<strong access signing secret>
JWT_REFRESH_SECRET=<different strong refresh signing secret>
JWT_ACCESS_TOKEN_TTL=<positive duration>
JWT_REFRESH_TOKEN_TTL=<positive duration>
CORS_ALLOWED_ORIGINS=<origins>
```

Keys must be independently generated and strong enough for the selected signing algorithm; neither has a default production value. Missing/weak keys, equal access/refresh keys or non-positive TTLs must fail startup without logging secrets. Exact TTL values and any issuer setting will be finalized in the implementation plan; no existing issuer contract is assumed here.

Identity owns a separate Redis namespace for the access blacklist, keyed only by JWT `jti` with a non-secret marker. Its expiry is the access JWT's absolute `exp`; TTL is the remaining lifetime, never a fixed refresh TTL or a fresh full access TTL. Never store a raw JWT. Configure Redis so blacklist entries survive restarts and are not evicted before expiry; lost or unverifiable revocation state must not be treated as an empty valid blacklist. No cache fallback is allowed for required blacklist checks.

The access-blacklist key format is `vanioak:identity:access-blacklist:<jti>`, with marker `1`. Atomic `SET NX PXAT` preserves the original absolute expiry on repeated writes. Redis and application clocks must be synchronized.

### Identity API-key lookup cache

`API_KEY_REDIS_CACHE_TTL` maps to `vanioak.identity.api-key-cache-ttl` (positive Duration, default `30s`). Identity reuses the existing Redis connection and prefix `vanioak:identity:api-key-lookup:` followed by the SHA-256 fingerprint. Raw keys are never stored in Redis. Positive context contains scoped IDs/statuses, key expiry and an absolute validation deadline bounded by this TTL and key expiry. Cache hits do not extend the deadline; application/environment status changes take effect within that bounded window.

Redis lookup/population failures fall back to authoritative PostgreSQL verification. If no usable cache or PostgreSQL result can establish validity, the dependency failure propagates. Rotate/revoke delete the old cache entry after PostgreSQL commit; failed invalidation is logged safely without failing the committed management operation. Existing entries still expire within the bounded TTL. This policy does not apply to the authoritative access-token blacklist, and is separate from Ingestion's local RAM cache settings.

### Initial Admin bootstrap

```text
BOOTSTRAP_ADMIN_ENABLED=false
BOOTSTRAP_ADMIN_USERNAME=<initial-admin-username>
BOOTSTRAP_ADMIN_PASSWORD=<secret>
```

Bootstrap is disabled by default. When explicitly enabled, Identity runs an `ApplicationRunner` after schema initialization and looks up the configured username. The username must be nonblank and at most 100 characters. If that username belongs to an ADMIN, bootstrap does nothing, preserving the password and status, including DISABLED. If it belongs to an ENGINEER, startup fails safely without promoting the account. If it does not exist, a non-empty password is required; Identity creates an ACTIVE ADMIN using the existing BCrypt encoder and saves it in a PostgreSQL transaction. Other users do not prevent creation. Invalid required configuration or a failed database operation fails startup without logging credentials or hashes.

The runtime properties are `vanioak.identity.bootstrap-admin.enabled`, `.username`, and `.password`, mapped to the three variables above. Neither username nor password has a default credential. Repeated startup with the same existing ADMIN username is a no-op; its bootstrap password may be removed. Disable bootstrap and remove the password after creation. Changing the configured username to a new name can create another ADMIN. Existing username uniqueness prevents duplicates of the same username; no custom concurrent-startup serialization or retry is provided, so a race may fail a startup. `ApplicationRunner` does not guarantee creation before the web server starts accepting requests. This remains a configuration-only path, not a public ADMIN-creation API.

## 8. Environment Files

Repository may contain:

```text
.env.example
```

It must contain variable names and safe placeholder values only. Real `.env` files and credentials must be ignored by Git.

## 9. Local run and smoke checks

### Swagger / runtime OpenAPI

`SWAGGER_ENABLED` defaults to `true` for local development. Set it to `false`
to disable both runtime API docs and Swagger UI.

With the backend running on the default port:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Runtime OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Swagger reflects only implemented controllers under `com.h.vanioak.api` with
paths matching `/api/v1/**`. Use **Try it out** to test those APIs.
`docs/contracts/openapi.yaml` remains the authoritative project target contract,
including APIs that have not yet been implemented.

### Run locally (PowerShell)

Prerequisites: JDK 21, Docker Desktop with Linux containers and Compose v2, and
network access for Maven artifacts/container images. Use the checked-in Maven
wrapper.

Run these commands from the repository root, `vanioak/`:

```powershell
Copy-Item .env.example .env
# Edit .env and set POSTGRES_PASSWORD, CLICKHOUSE_PASSWORD and RABBITMQ_PASSWORD.
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --wait --wait-timeout 180
docker compose --env-file .env ps
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/start.ps1
```

Use `.env.example` as a template and supply local passwords. Compose reads `.env`
for service settings; Spring Boot does not load that file automatically. The
PowerShell start/smoke scripts import literal values into the backend process,
with existing process environment settings taking precedence. Use single-quoted
literal values when secrets contain `$`, `#` or spaces; variable interpolation and
escape sequences are not supported by the script loader.

The start script runs `spring-boot:run`. Both scripts accept a custom `-EnvFile`
path; the default is the root `.env`. The loader accepts literal `KEY=value`
entries, optional surrounding quotes and standalone comments.

Use Ctrl+C to stop the backend. To stop infrastructure while retaining its named
volumes and data:

```powershell
docker compose --env-file .env stop
```

Changing `.env` does not change an existing PostgreSQL user's password; database
and user credentials are initialized when its data volume is first created.

### Smoke verification

Ordinary tests and packaging do not need Docker or an environment file:

```powershell
Set-Location backend
./mvnw.cmd -B -ntp verify
Set-Location ..
```

After the four services are healthy, run real infrastructure checks from the
repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/smoke.ps1
```

The smoke script loads the environment and invokes `./mvnw.cmd -B -ntp -Psmoke
verify` from `backend/`. The explicit Maven profile runs `BackendFoundationIT`
alongside ordinary unit tests. It checks application startup, PostgreSQL
`SELECT 1`, Redis `PING`, an authenticated RabbitMQ connection/channel, an
authenticated ClickHouse `SELECT 1`, Flyway/schema verification, and repeat
application startup.

Smoke exits `0` only when all checks pass. Missing credentials, failed startup,
authentication failures, unavailable dependencies, or migration mismatch return
nonzero. The integration-test fork has a 180-second timeout. Reports appear in
`backend/target/surefire-reports/` and `backend/target/failsafe-reports/`.

For isolated verification, use a separate Compose project, separate host ports,
and an ignored environment file, for example `docker compose -p
vanioak-phase0-check --env-file .local/phase0.env ...`. Keep the same project name
and environment file for start, restart, status and stop commands.

### Local connectivity settings

`POSTGRES_URL` is a PostgreSQL JDBC URL. `CLICKHOUSE_URL` is an HTTP(S)
server URL such as `http://localhost:8123`, without credentials, query parameters,
fragments or additional paths. Its username/password are sent separately for an
authenticated connectivity query.

Compose publishes ports on loopback only. `POSTGRES_DB` defaults to `vanioak`.
`POSTGRES_HOST_PORT`, `CLICKHOUSE_HTTP_PORT`, `REDIS_HOST_PORT`,
`RABBITMQ_HOST_PORT`, and `RABBITMQ_MANAGEMENT_PORT` default to `5432`, `8123`,
`6379`, `5672`, and `15672`. When changing a host port, change the backend URL or
port setting to match. A host backend uses `localhost`; Docker network clients
would use service names and container ports. RabbitMQ uses a non-guest account.

Foundation connectivity timeout settings are:

| Key | Default | Purpose |
|---|---|---|
| `POSTGRES_POOL_CONNECTION_TIMEOUT_MS` | `5000` | Datasource pool connection wait |
| `POSTGRES_CONNECT_TIMEOUT_SECONDS` | `5` | PostgreSQL connection establishment |
| `POSTGRES_SOCKET_TIMEOUT_SECONDS` | `10` | PostgreSQL socket read timeout |
| `REDIS_CONNECT_TIMEOUT` | `5s` | Redis connection establishment |
| `REDIS_COMMAND_TIMEOUT` | `5s` | Redis command timeout |
| `RABBITMQ_CONNECT_TIMEOUT` | `5s` | RabbitMQ connection establishment |
| `RABBITMQ_CHANNEL_RPC_TIMEOUT` | `5s` | RabbitMQ channel RPC timeout |
| `CLICKHOUSE_CONNECT_TIMEOUT` | `5s` | ClickHouse HTTP connection establishment |
| `CLICKHOUSE_REQUEST_TIMEOUT` | `5s` | ClickHouse HTTP request timeout |
