# REST API Contract v1

[OpenAPI](openapi.yaml) is the normative REST contract. This guide explains usage and behavior that is not conveniently expressed in its schemas.

## 1. API conventions

- Base path: `/api/v1`
- Content-Type: `application/json`
- Timestamps: ISO-8601 UTC, e.g. `2026-10-02T08:00:00.123Z`
- IDs: UUID strings
- Pagination: opaque cursor pagination
- Maximum page size: 100 records unless an endpoint states otherwise
- Human-user APIs use `Authorization: Bearer <access_token>`
- Log ingestion APIs use `X-API-Key: <application-api-key>`
- API keys are scoped to exactly one environment; that environment belongs to one application.
- Every REST response, including errors, uses the common `success`, `message`, `data` envelope defined in [OpenAPI](openapi.yaml).

Disabled-state behavior and its `401`/`403` outcomes follow [Security](../quality/security.md#disabled-state); disabling a scope does not delete its history. Input length violations defined in OpenAPI return `400` with the normal validation envelope before persistence.

### Error response

```json
{
  "success": false,
  "message": "The supplied API key is invalid",
  "data": {
    "code": "INVALID_API_KEY",
    "request_id": "2bf6e2c8-...",
    "details": {}
  }
}
```

Rules:

- `success` is always present and is `true` for successful responses, `false` for errors.
- `message` is always present and is safe for client display/logging.
- `data` is always present. Use an object, array, or `null` depending on the endpoint.
- Do not introduce another top-level field such as `error`, `meta`, or `errors`.
- Validation and business-error details belong under `data.details`.
- `request_id` should be returned in `data` for error responses and preferably also in successful responses where useful for troubleshooting.

## 2. Authentication

### POST `/api/v1/auth/login`

Authenticate a human user.

Request and response shapes are `LoginRequest` and `LoginResponse` in [OpenAPI](openapi.yaml); the response supplies the Bearer token for human-user APIs.

Errors: `400`, `401`.

## 3. Applications and environments

### GET `/api/v1/applications`

Returns applications visible to the current user.

- ADMIN: all applications.
- ENGINEER: applications assigned through `user_application_access`.

Filters, pagination, and the application/environment response shape are defined for this operation in [OpenAPI](openapi.yaml).

### POST `/api/v1/applications`

ADMIN only.

Request and response shapes are `CreateApplicationRequest` and `ApplicationResponse` in [OpenAPI](openapi.yaml).

### PATCH `/api/v1/applications/{applicationId}`

ADMIN only. Supports updating name/description/status.

Response `200` follows the common envelope; `data` contains the updated application.

### POST `/api/v1/applications/{applicationId}/environments`

ADMIN only.

Request:

```json
{"name":"STAGING"}
```

Allowed names in MVP: `DEV`, `TEST`, `STAGING`.

Response `201` follows the common envelope; `data` contains the created environment.

### PATCH `/api/v1/applications/{applicationId}/environments/{environmentId}`

ADMIN only. Supports updating environment status (`ACTIVE|DISABLED`).

### API key management

ADMIN only.

- `GET /api/v1/applications/{applicationId}/environments/{environmentId}/api-keys`
  - lists credential metadata only; plaintext keys are never returned.
- `POST /api/v1/applications/{applicationId}/environments/{environmentId}/api-keys`
  - creates a credential; optional `expires_at`.
  - response returns the plaintext `api_key` exactly once together with credential metadata.
- `POST /api/v1/api-keys/{credentialId}/rotate`
  - revokes/replaces the old credential and returns the new plaintext key exactly once.
- `DELETE /api/v1/api-keys/{credentialId}`
  - revokes the credential; no physical deletion is required.

### POST `/api/v1/applications/{applicationId}/engineers/{userId}`

ADMIN only. Assign an engineer to an application.

Response `200` uses `ActionResponse` in [OpenAPI](openapi.yaml).

### DELETE `/api/v1/applications/{applicationId}/engineers/{userId}`

ADMIN only. Remove engineer access.

Response `200` uses `ActionResponse` in [OpenAPI](openapi.yaml).

## 4. Ingestion

### POST `/api/v1/logs`

Accept one structured log.

Headers:

```text
X-API-Key: lm_...
```

Request:

```json
{
  "application": "payment-service",
  "environment": "STAGING",
  "host_ip": "10.10.20.15",
  "level": "ERROR",
  "message": "Database connection timeout",
  "timestamp": "2026-10-02T08:01:22.123Z",
  "trace_id": "abc-123",
  "metadata": {
    "endpoint": "/api/payments",
    "status_code": 500
  }
}
```

The application/environment in the payload are validated against the API-key scope. Lowercase environment aliases are normalized to canonical `DEV`, `TEST`, or `STAGING` before scope validation. The API key is authoritative.

Response `202`:

```json
{
  "success": true,
  "message": "Log accepted for processing",
  "data": {
    "accepted": true,
    "request_id": "2bf6e2c8-..."
  }
}
```

The response means the log was durably accepted by RabbitMQ publisher-confirm semantics; it does **not** mean it is already in ClickHouse.

Errors:

- `400` malformed/invalid request
- `401` missing/invalid/revoked/expired API key
- `403` authenticated credential but application/environment scope mismatch
- `429` backpressure / queue capacity policy
- `503` RabbitMQ unavailable

### POST `/api/v1/logs/batch`

Headers: `X-API-Key`

Wrap the single-log input above in the `logs` array defined by `LogBatchInput` in [OpenAPI](openapi.yaml).

MVP limit: 1,000 log records per request.

Response `202`:

```json
{
  "success": true,
  "message": "Log batch accepted for processing",
  "data": {
    "accepted": true,
    "accepted_count": 1,
    "request_id": "2bf6e2c8-..."
  }
}
```

## 5. Log search

### GET `/api/v1/logs`

Query parameters and pagination limits are defined in [OpenAPI](openapi.yaml). `message` performs substring search; if `from` is supplied, `to` is required.

Example:

```text
GET /api/v1/logs?application_id=a1...&environment_id=e3...&level=ERROR&from=2026-10-02T08:00:00Z&to=2026-10-02T09:00:00Z&cursor=eyJ...
```

Response `200` uses `LogListResponse` in [OpenAPI](openapi.yaml), with log items and the next cursor.

Authorization is applied before returning results. An ENGINEER can only retrieve logs belonging to applications assigned to that engineer. Results are ordered by `timestamp DESC, event_id DESC`; cursors preserve this order and the original filters.

## 6. Incidents

### GET `/api/v1/incidents`

Filters and pagination are defined for this operation in [OpenAPI](openapi.yaml).

Response `200` uses the common envelope with `data.items` and `data.next_cursor`.

### GET `/api/v1/incidents/{incidentId}`

Returns incident details and recent incident events.

Response `200` uses `IncidentResponse` in [OpenAPI](openapi.yaml), including recent incident events.

## 7. Alert rules

### GET `/api/v1/alert-rules`

ADMIN only.

Response `200` uses the common envelope.

### POST `/api/v1/alert-rules`

ADMIN only.

Request:

```json
{
  "environment_id": "e3...",
  "level": "ERROR",
  "threshold": 10,
  "window_seconds": 60,
  "cooldown_seconds": 300,
  "resolve_after_seconds": 300,
  "enabled": true
}
```

Response `201` uses the common envelope; `data` contains the created rule.

MVP rule uniqueness: one rule per `(environment, level)`; update/enable/disable that rule instead of creating duplicates.

### PATCH `/api/v1/alert-rules/{ruleId}`

ADMIN only. Response `200` uses the common envelope; `data` contains the updated rule.

### DELETE `/api/v1/alert-rules/{ruleId}`

ADMIN only; recommended behavior is disable rather than physical delete.

Response `200` uses the common envelope; `data` contains the resulting rule state.

## 8. Analytics

### GET `/api/v1/analytics/health`

Query parameters and the required time range are defined in [OpenAPI](openapi.yaml); the MVP bucket is hourly.

Response `200`:

```json
{
  "success": true,
  "message": "Health analytics retrieved successfully",
  "data": {
    "bucket": "hour",
    "items": [
      {
        "application_id": "a1...",
        "environment_id": "e3...",
        "timestamp": "2026-10-02T08:00:00Z",
        "total_logs": 100000,
        "error_logs": 120,
        "critical_logs": 2,
        "log_error_rate": 0.00122
      }
    ]
  }
}
```

## 9. Error codes

Recommended `data.code` values:

- `INVALID_REQUEST`
- `VALIDATION_ERROR`
- `UNAUTHORIZED`
- `FORBIDDEN`
- `RESOURCE_NOT_FOUND`
- `CONFLICT`
- `INVALID_API_KEY`
- `INGESTION_BACKPRESSURE`
- `DEPENDENCY_UNAVAILABLE`
- `INTERNAL_ERROR`

Validation errors use the error envelope shown above; field-level messages belong in `data.details`.

## 10. HTTP status summary

- `200` successful read/update/action with a JSON response envelope
- `201` resource created
- `202` asynchronously accepted for processing
- `400` validation error
- `401` unauthenticated / invalid credential
- `403` authenticated but not authorized
- `404` resource not found
- `409` state/conflict violation
- `429` backpressure/rate limit
- `500` unexpected server error
- `503` dependency unavailable

There are no `204 No Content` responses in this API contract because every REST response must use the common JSON envelope.
