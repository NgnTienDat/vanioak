# REST API Contract v1

This document is the source of truth for REST APIs of the Log Monitoring Platform MVP.

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
- **Every REST response, including errors, MUST use the same top-level response envelope:**

```json
{
  "success": true,
  "message": "Human-readable message",
  "data": {}
}
```

### Success response

```json
{
  "success": true,
  "message": "Logs retrieved successfully",
  "data": {
    "items": [],
    "next_cursor": null
  }
}
```

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

Request:

```json
{
  "username": "engineer01",
  "password": "secret"
}
```

Response `200`:

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "access_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 3600,
    "user": {
      "id": "2b2b6d4a-...",
      "username": "engineer01",
      "role": "ENGINEER"
    }
  }
}
```

Errors: `400`, `401`.

## 3. Applications and environments

### GET `/api/v1/applications`

Returns applications visible to the current user.

- ADMIN: all applications.
- ENGINEER: applications assigned through `user_application_access`.

Query:

- `status` optional: `ACTIVE|DISABLED`
- `cursor` optional
- `limit` optional, max 100

Response `200`:

```json
{
  "success": true,
  "message": "Applications retrieved successfully",
  "data": {
    "items": [
      {
        "id": "a1...",
        "name": "payment-service",
        "description": "Payment backend",
        "status": "ACTIVE",
        "environments": [
          {"id": "e1...", "name": "DEV", "status": "ACTIVE"},
          {"id": "e2...", "name": "TEST", "status": "ACTIVE"},
          {"id": "e3...", "name": "STAGING", "status": "ACTIVE"}
        ]
      }
    ],
    "next_cursor": "eyJpZCI6ImUx..."
  }
}
```

### POST `/api/v1/applications`

ADMIN only.

Request:

```json
{
  "name": "payment-service",
  "description": "Payment backend"
}
```

Response `201`:

```json
{
  "success": true,
  "message": "Application created successfully",
  "data": {
    "id": "a1...",
    "name": "payment-service",
    "description": "Payment backend",
    "status": "ACTIVE"
  }
}
```

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

Response `200`:

```json
{
  "success": true,
  "message": "Engineer assigned to application successfully",
  "data": {
    "user_id": "u1...",
    "application_id": "a1..."
  }
}
```

### DELETE `/api/v1/applications/{applicationId}/engineers/{userId}`

ADMIN only. Remove engineer access.

Response `200`:

```json
{
  "success": true,
  "message": "Engineer access removed successfully",
  "data": {
    "user_id": "u1...",
    "application_id": "a1..."
  }
}
```

## 4. Ingestion

### POST `/api/v1/ingest/logs`

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

The application/environment in the payload are validated against the API-key scope. The API key is authoritative.

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

### POST `/api/v1/ingest/logs/batch`

Headers: `X-API-Key`

Request:

```json
{
  "logs": [
    {
      "application": "payment-service",
      "environment": "STAGING",
      "host_ip": "10.10.20.15",
      "level": "ERROR",
      "message": "Database connection timeout",
      "timestamp": "2026-10-02T08:01:22.123Z",
      "trace_id": "abc-123",
      "metadata": {}
    }
  ]
}
```

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

Supported query parameters:

- `application_id` optional
- `environment_id` optional
- `level` optional: `INFO|WARN|ERROR|CRITICAL`
- `from` required when searching large ranges; ISO-8601 UTC
- `to` required when `from` is present
- `trace_id` optional
- `message` optional substring search
- `cursor` optional
- `limit` optional, max 100

Example:

```text
GET /api/v1/logs?application_id=a1...&environment_id=e3...&level=ERROR&from=2026-10-02T08:00:00Z&to=2026-10-02T09:00:00Z&cursor=eyJ...
```

Response `200`:

```json
{
  "success": true,
  "message": "Logs retrieved successfully",
  "data": {
    "items": [
      {
        "event_id": "e6...",
        "application_id": "a1...",
        "environment_id": "e3...",
        "host_ip": "10.10.20.15",
        "level": "ERROR",
        "message": "Database connection timeout",
        "timestamp": "2026-10-02T08:01:22.123Z",
        "trace_id": "abc-123",
        "metadata": {
          "endpoint": "/api/payments",
          "status_code": 500
        },
        "received_at": "2026-10-02T08:01:22.150Z",
        "processed_at": "2026-10-02T08:01:22.310Z"
      }
    ],
    "next_cursor": "eyJ0cyI6MT..."
  }
}
```

Authorization is applied before returning results. An ENGINEER can only retrieve logs belonging to applications assigned to that engineer.

## 6. Incidents

### GET `/api/v1/incidents`

Query:

- `application_id` optional
- `environment_id` optional
- `status` optional: `OPEN|RESOLVED`
- `level` optional: `ERROR|CRITICAL`
- `from` optional
- `to` optional
- `cursor` optional
- `limit` optional, max 100

Response `200` uses the common envelope with `data.items` and `data.next_cursor`.

### GET `/api/v1/incidents/{incidentId}`

Returns incident details and recent incident events.

Response `200`:

```json
{
  "success": true,
  "message": "Incident retrieved successfully",
  "data": {
    "id": "i1...",
    "application": {"id": "a1...", "name": "payment-service"},
    "environment": {"id": "e3...", "name": "STAGING"},
    "level": "ERROR",
    "status": "OPEN",
    "fingerprint": "sha256...",
    "message": "Database connection timeout",
    "error_count": 5281,
    "first_seen_at": "2026-10-02T08:01:22Z",
    "last_seen_at": "2026-10-02T08:05:12Z",
    "resolved_at": null,
    "events": []
  }
}
```

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

Query:

- `application_id` optional
- `environment_id` optional
- `from` required
- `to` required
- `bucket=hour` in MVP

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

Example validation error:

```json
{
  "success": false,
  "message": "Request validation failed",
  "data": {
    "code": "VALIDATION_ERROR",
    "request_id": "2bf6e2c8-...",
    "details": {
      "level": "must be one of INFO, WARN, ERROR, CRITICAL",
      "timestamp": "must be a valid ISO-8601 timestamp"
    }
  }
}
```

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
