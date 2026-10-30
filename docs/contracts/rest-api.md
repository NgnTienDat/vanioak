# REST API Guide v1

`openapi.yaml` is the authoritative REST contract.
This document summarizes endpoint ownership, authorization, and important API behavior that is useful to human readers.

These sections match the resource-oriented OpenAPI tags for readability. Architectural
ownership remains unchanged: Auth, Users, Applications, and API Keys belong to Identity;
Log Ingestion belongs to Ingestion; Log Search and Analytics belong to Analysis;
Incidents and Alert Rules belong to Alert.

## 1. Conventions

- Base path: `/api/v1`
- JSON REST APIs use the common response envelope:
  `{ success, message, data }`.
- Errors set `success` to `false`. Field validation returns `400`, message
  `Validation failed`, and a field-to-message map in `data` (empty when there are
  no field errors), without rejected values.
- Business error status and client-safe message come directly from the module's
  `ErrorCode`; `data` is `null`. Unexpected errors return `500` with a generic safe
  message and `data: null`. The `data` key is always present.
- Human APIs use Bearer authentication unless the endpoint is public.
- Ingestion uses `X-API-Key`.
- Pagination uses opaque cursors.
- SSE is documented separately in `sse-api.md`.

## 2. Auth

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/auth/login` | Public | Authenticate and issue access + refresh tokens |
| POST | `/auth/refresh` | Refresh token | Rotate refresh token and issue a new token pair |
| POST | `/auth/logout` | Refresh token; optional Bearer access token | Revoke the current refresh session and supplied valid access token |

Behavior:
- Login issues a short-lived access JWT and a refresh JWT.
- Refresh JWTs are single-use and rotate within the current session; reuse revokes that refresh session and returns `401`.
- Refresh/logout authenticate `refresh_token` in the JSON body; no access token is required. Logout accepts the current access token through the optional Bearer header and revokes it if valid, unexpired and for the same user; a valid token for a different user returns `401`. An absent, invalid or expired access token does not block logout with a valid refresh token.
- Logout revokes the current refresh session; other sessions remain active. A supplied valid access token cannot be reused after successful logout. Other access tokens remain subject to expiry and normal authorization checks; refresh rotation alone does not revoke them.
- Disabled users cannot login or refresh. Protected requests use current user role/status.

## 3. Users

ADMIN manages ENGINEER users only.

| Method | Path | Purpose |
|---|---|---|
| GET | `/users` | List engineers |
| POST | `/users` | Create engineer |
| GET | `/users/{userId}` | Get engineer |
| PATCH | `/users/{userId}` | Update engineer |
| DELETE | `/users/{userId}` | Disable engineer |

`DELETE` is a soft delete and sets the user to `DISABLED`.
ADMIN users are created outside these REST operations.

## 4. Applications

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/applications` | ADMIN / assigned ENGINEER | List visible applications |
| POST | `/applications` | ADMIN | Create application |
| PATCH | `/applications/{applicationId}` | ADMIN | Update application |

Creating an application also creates exactly three environments:
`DEV`, `TEST`, and `STAGING`.

There is no REST API for creating or changing environments independently.

Environment IDs remain part of the model because ingestion credentials, logs,
search, and alert rules are environment-scoped.

### Engineer assignments

| Method | Path | Access |
|---|---|---|
| GET | `/applications/{applicationId}/engineers` | ADMIN |
| POST | `/applications/{applicationId}/engineers/{userId}` | ADMIN |
| DELETE | `/applications/{applicationId}/engineers/{userId}` | ADMIN |

Assignments are application-scoped and grant access to all environments of the application.

The roster GET reads actual assignments and returns `data: {items, next_cursor}`.
Each item contains only `id`, `username`, and `status` (`ACTIVE` or `DISABLED`).
Assigned disabled Engineers and assignments on disabled applications remain visible;
there is no status filter, environment filter or total-count field. It never grants
access or changes roles. ENGINEER callers are forbidden even for their own applications.

Items are ordered by user UUID ascending. Optional `limit` defaults to 50 and must
be 1–100; optional `cursor` is opaque and must be repeated unchanged for the same
application. Pagination reflects current assignments without a cross-page snapshot
guarantee. Existing applications with no assignments return `items: []` and
`next_cursor: null`; missing applications return `404`. Malformed application IDs,
invalid cursors or limits return `400`; normal Bearer `401`/`403` and safe dependency
failure envelopes apply. Existing assignment POST/DELETE semantics are unchanged.

## 5. API Keys

| Method | Path | Access |
|---|---|---|
| GET | `/applications/{applicationId}/environments/{environmentId}/api-keys` | ADMIN |
| POST | `/applications/{applicationId}/environments/{environmentId}/api-keys` | ADMIN |
| POST | `/api-keys/{credentialId}/rotate` | ADMIN |
| DELETE | `/api-keys/{credentialId}` | ADMIN |

API keys are scoped to one environment.
Plaintext keys are returned only on create/rotate.

## 6. Log Ingestion

| Method | Path | Authentication |
|---|---|---|
| POST | `/logs` | API key |
| POST | `/logs/batch` | API key |

A successful `202` means RabbitMQ has confirmed acceptance.
It does not mean the log has already been persisted to ClickHouse.

Batch ingestion accepts at most 1,000 logs.

## 7. Log Search

| Method | Path | Purpose |
|---|---|---|
| GET | `/logs` | Search logs |

ENGINEER queries are restricted to assigned applications.

Log search ordering is:

`timestamp DESC, event_id DESC`

Cursor pagination preserves that ordering and the original filters.

## 8. Analytics

| Method | Path | Purpose |
|---|---|---|
| GET | `/analytics/health` | Hourly health analytics |

ENGINEER queries are restricted to assigned applications.

## 9. Incidents

| Method | Path |
|---|---|
| GET | `/incidents` |
| GET | `/incidents/{incidentId}` |
| PATCH | `/incidents/{incidentId}/status` |

ADMIN sees all incidents.
ENGINEER sees incidents for assigned applications only.

Incident list filters are combined within the authorized scope before pagination.
`from` and `to` constrain `last_seen_at` using `[from,to)`; either bound may be
omitted. Ordering is `last_seen_at DESC, id DESC`, preserved by the opaque cursor
and repeated filters. Concurrent updates may move an Incident's position; pages
do not provide a snapshot across requests.

List items omit `events`. Incident detail includes the newest **100** lifecycle
events, ordered `event_at DESC, id DESC`; fewer are returned when less history
exists. This is recent history, not a complete-history guarantee. Older events
remain stored. No separate history endpoint or pagination field is introduced.

## 10. Alert Rules

| Method | Path | Access |
|---|---|---|
| GET | `/alert-rules` | ADMIN |
| POST | `/alert-rules` | ADMIN |
| PATCH | `/alert-rules/{ruleId}` | ADMIN |
| DELETE | `/alert-rules/{ruleId}` | ADMIN |

Alert rules are scoped by environment and level.

## 11. Contract details

Exact request/response schemas, validation constraints, query parameters,
HTTP status codes, security declarations, and payload field definitions are
defined in `openapi.yaml`.

SSE behavior is defined in `sse-api.md`.
### Manual Incident status update

PATCH /api/v1/incidents/{incidentId}/status accepts only {"status":"RESOLVED"}. Active ADMIN/assigned ENGINEER may close an existing Incident in disabled scopes too. Unknown fields/status, null/malformed input are 400; standard Bearer 401/403 applies. Missing/foreign ID is ADMIN404/ENGINEER403. Success200 returns success/message/data with id,status,resolved_at,resolution_source,resolved_by_user_id. Source is MANUAL/AUTOMATIC or null for legacy; actor is nullable except MANUAL. Duplicate requests preserve committed audit and emit no extra history/SSE. GET shapes/cursors remain unchanged; new audit is also visible in existing resolved history metadata.
