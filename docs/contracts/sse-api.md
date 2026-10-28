# SSE Realtime Contract v1

## Connection

Endpoint:

```text
GET /api/v1/realtime/stream
```

Authentication uses the same human-user Bearer token as REST. The MVP client must use an SSE implementation that can send:

```text
Authorization: Bearer <access_token>
```

The server enforces application authorization for every event; authorization is never delegated to the frontend.

## Subscription model

By default, the connection receives events for applications accessible to the authenticated user.

Optional filters:

```text
GET /api/v1/realtime/stream?application_id=a1...&environment_id=e3...&level=ERROR
```

Filters may narrow the authorized scope but never expand it.

An omitted scope follows the user's current application assignments. An explicit
application/environment scope is checked before the stream starts; active
connections are checked again before each data event. A revoked assignment,
disabled user, expired or logged-out Bearer token, or unavailable authorization
dependency cannot authorize a queued event. Idle connections recheck the session
and any explicit scope on heartbeat. Previously transmitted bytes cannot be
retracted. A reconnect creates a new authorized subscription.

## Event types

### `log`

```text
event: log
data: {"event_id":"e6...","application_id":"a1...","environment_id":"e3...","host_ip":"10.10.20.15","level":"ERROR","message":"Database connection timeout","timestamp":"2026-10-02T08:01:22.123Z","trace_id":"abc-123","metadata":{}}
```

### `incident.created`

```text
event: incident.created
data: {"incident_id":"i1...","application_id":"a1...","environment_id":"e3...","level":"ERROR","status":"OPEN","message":"Database connection timeout","error_count":10}
```

### `incident.updated`

```text
event: incident.updated
data: {"incident_id":"i1...","error_count":250,"last_seen_at":"2026-10-02T08:02:10Z"}
```

### `incident.resolved`

```text
event: incident.resolved
data: {"incident_id":"i1...","status":"RESOLVED","resolved_at":"2026-10-02T08:10:00Z"}
```

## Connection behavior

- `Content-Type: text/event-stream`
- Send heartbeat comments periodically.
- Client reconnect is expected.
- MVP does not require SSE replay/`Last-Event-ID`; missed history is recovered through `/logs` and `/incidents`.
- Realtime is best-effort; ClickHouse remains the source of truth for logs.
- Realtime may use bounded buffering/batching/throttling to protect server and browser memory.
- Before the stream commits, malformed parameters return JSON `400`, missing or
  invalid Bearer credentials return JSON `401`, forbidden Engineer/disabled access
  returns JSON `403`, an ADMIN's nonexistent explicit scope retains Identity
  `404`, and not-ready or admission exhaustion returns JSON `503`. Other
  dependency failures use the existing safe error envelope. After commit the
  server closes a failing stream; it does not rewrite HTTP status or add an
  undocumented SSE error event.
- CORS applies only to this exact path and explicitly configured origins.
  Authorization is sent as a header; a query token or cookie is not accepted.
- This MVP phase has a single Realtime-enabled instance. Its stream readiness
  requires the exclusive processed-event consumer to be registered; the
  connection endpoint remains unavailable until that later consumer gate passes.
