# Backend MVP integration handoff

This guide covers the accepted development MVP. Telegram, physical TTL expiration
AC14, performance/demo acceptance and Docker packaging remain separate work.
Historical native FAIL verdicts are unchanged. OpenAPI and the SSE contract under
`docs/contracts/` are authoritative; a successful build is not a live readiness check.

## Runtime

Use Java21/Maven and the existing backend JAR or Maven launch workflow. Root
`compose.yaml` supplies infrastructure only. It is not a backend deployment.
Supply real secrets through environment/process configuration; `.env.example`
lists baseline variables. Never point test credentials at the ordinary project.

After infrastructure/schema and operational scope are approved, build the existing
artifact with `mvn -o -f backend/pom.xml -DskipTests package`, then launch
`java -jar backend/target/backend-0.0.1-SNAPSHOT.jar` with the reviewed environment
and canonical flags below. For the existing migrated fixture, use Flyway=false,
Hibernate validate and RabbitAdmin dynamic=false. Do not reset/start/recreate the
accepted fixture as a convenience; its lifecycle is separately controlled. A new
deployment needs explicit schema/topology provisioning, not an assumed first-start
side effect. These command examples do not authorize a launch or marker recovery.

Required infrastructure configuration:

| Concern | Actual configuration |
|---|---|
| PostgreSQL | POSTGRES_URL/USERNAME/PASSWORD → spring.datasource.* |
| ClickHouse | CLICKHOUSE_URL/USERNAME/PASSWORD → vanioak.clickhouse.* |
| Redis | REDIS_HOST/PORT → spring.data.redis.*; override spring.data.redis.database explicitly for an approved isolated target |
| RabbitMQ | RABBITMQ_HOST/PORT/USERNAME/PASSWORD → spring.rabbitmq.*; virtual-host must be supplied explicitly for an isolated fixture |
| JWT | JWT_ACCESS_SECRET, JWT_REFRESH_SECRET, JWT_ACCESS_TOKEN_TTL, JWT_REFRESH_TOKEN_TTL |
| Initial Admin | BOOTSTRAP_ADMIN_ENABLED/USERNAME/PASSWORD; configured normal bootstrap, no forged principal |
| Retention | LOG_RETENTION_DAYS=7; scheduled read-only metadata check after1m, then fixed delay1h |

Consumer enablement is explicit; defaults do not start a full live pipeline:

| Property | Default / requirement |
|---|---|
| vanioak.processing.consumer-enabled | false; PROCESSING_CONSUMER_ENABLED alias in application.yml |
| vanioak.processing.consumer-prefetch | 16; batching remains1000 logs/2000ms |
| vanioak.alert.consumer-enabled | false; ALERT_CONSUMER_ENABLED alias supported |
| vanioak.alert.fence-path | no default; absolute reviewed path, existing persistent parent |
| vanioak.realtime.enabled | false; use canonical property or VANIOAK_REALTIME_ENABLED |
| vanioak.realtime.fence-path | no default; use canonical property or VANIOAK_REALTIME_FENCEPATH |
| vanioak.realtime.allowed-origins | empty; explicit origins only if separately approved |
| vanioak.alert.resolver-enabled | false unless explicitly true; KEEP FALSE for shared-fixture B1 |
| spring.rabbitmq.dynamic | application default true; explicitly false for the preprovisioned B1 fixture |
| spring.flyway.enabled | application default true; explicitly false for B1's existing migrated DB |
| spring.jpa.hibernate.ddl-auto | none by application default; validate for B1 |

Use `--canonical.property=value` for non-secret flags to avoid ambiguous aliases.
The B1 launch wrapper must verify exact isolated endpoints before Spring starts:
PG127.0.0.1:25432/processing_test, Redis127.0.0.1:26379/DB0,
Rabbit127.0.0.1:25672/vhost processing-test, ClickHouse127.0.0.1:28123.
These are test-fixture endpoints, not a general deployment prescription.
Correlated publisher confirms, returns and mandatory routing stay enabled.
Do not use RabbitAdmin initialization/reset to make a failing preflight pass.

## Human API and ingestion

All paths below are relative to `/api/v1`; JSON uses
`{"success":true|false,"message":"...","data":...}`. Validation errors use the
documented400 envelope;401 means authentication failed,403 means access denied.

| Use | Endpoints / authentication |
|---|---|
| Session | POST /auth/login, /auth/refresh, /auth/logout; send current Bearer on logout to revoke that access JWT too |
| Users | /users and /users/{id}; Admin manages Engineers |
| Applications/access | GET/POST /applications, PATCH /applications/{id}, POST/DELETE /applications/{id}/engineers/{userId} |
| API keys | /applications/{appId}/environments/{envId}/api-keys; /api-keys/{id}/rotate and DELETE /api-keys/{id}; Admin only |
| Search | GET /logs; human Bearer |
| Analytics | GET /analytics/health?from=...&to=...&bucket=hour; human Bearer |
| Incidents | GET /incidents and /incidents/{id}; human Bearer |
| Rules | /alert-rules and /alert-rules/{id}; Admin only |
| Log sources | POST /logs or /logs/batch; X-API-Key, not human Bearer |

Login returns user identity/role and token pair; refresh rotates the refresh token.
Keep the newest pair; don't retry a used refresh token. Authorization is checked
against current server state. Engineers see only assigned applications and their
environments; client filters only narrow that scope. Applications provide fixed
DEV/TEST/STAGING environments. No separate environment CRUD is in this MVP API.

Use `data.items` and opaque `data.next_cursor`; retain the same filters when paging.
Do not manufacture/decode cursors or infer numeric offsets. Time bounds are UTC
and half-open `[from,to)` where documented. Incident list omits lifecycle history;
detail includes `events`. Analytics `log_error_rate` is a ratio, not a percentage.
Ingestion202 confirms enqueue acceptance, not immediate search/Incident visibility.

## Browser / SSE

Prefer one browser origin with a development/reverse proxy for `/api/v1`.
Preserve Authorization and SSE response headers; disable proxy buffering/compression
that batches stream delivery, allow long-lived reads, and cancel upstream when the
client disconnects. REST has no general cross-origin CORS policy. A direct
cross-origin design needs separate explicit-origin/security review.

Connect `GET /api/v1/realtime/stream` using a fetch-stream/SSE client that can send
`Authorization: Bearer ...`. Native EventSource cannot attach that header; query
tokens/cookies are not accepted. Check HTTP status before parsing SSE. Optional
application_id/environment_id/level filters stay server-authorized.

Expect `log`, `incident.created`, `incident.updated`, `incident.resolved` and
heartbeat comments. Do not assume global order between log and Incident branches.
SSE is best-effort, has bounded buffers and no replay guarantee/required SSE id.
On disconnect/expiry, refresh credentials if appropriate, reconnect with bounded
backoff and reconcile missed logs/Incidents via REST. A503 can mean Realtime is
disabled/not registered or capacity is exhausted; enabling a flag alone is not readiness.

## Recovery / B1 execution gate

Alert and Realtime retain startup markers even after clean shutdown. Their fixed
workspace paths are `.local/alert-recovery.fence` and
`.local/realtime-native-happy-20261026/realtime.fence`. Existing markers require
fresh reconciliation and separately approved exact-object archive/release. Do not
delete, rotate, recreate or change paths to enable development restarts.

The global Alert resolver scans all OPEN Incidents in its database. Keep it off
for the shared-fixture smoke to preserve historical due Incidents. Enabling it
later needs explicit data ownership/isolation approval.

`BackendMvpSmokeIT#ingestedErrorsReachSearchIncidentsAndSse` is preparation only.
Execution requires separate approval, one-shot/preflight records, reviewed recovery,
an independently bounded launcher/postflight and process-only secrets. Its3 HTTP
ERROR logs exercise the normal pipeline; it must never be run as an ordinary unit
test or without its explicit runtime authorization gate.

Future launcher bindings: `B1_NATIVE_APPROVED=true`, `B1_NATIVE_RUN=<UUID>` and
`B1_NATIVE_EVIDENCE=<new evidence directory>`, plus existing
`PROCESSING_POSTGRES_TEST_{URL,USERNAME,PASSWORD}`,
`PROCESSING_CLICKHOUSE_TEST_{URL,USERNAME,PASSWORD}`,
`PROCESSING_REDIS_TEST_{HOST,PORT,DATABASE}` and
`PROCESSING_RABBITMQ_TEST_{HOST,PORT,VHOST,USERNAME,PASSWORD}` variables.
`preflight.json`, `one-shot.json` and `recovery-result.json` must each bind the
same `run_id`, exact selector and truthful `pass=true` based on reviewed checks.
The test creates `runtime-activation.claim` once; never reuse the directory to
relaunch. The external postflight must independently verify PID/socket retirement,
historical records, fixture/topology preservation and new same-path marker identity.
