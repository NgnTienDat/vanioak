# Realtime Module

## Purpose
Deliver normalized log events and Incident events to authenticated dashboard users through SSE.

## Input
- Normalized log events from `realtime.queue`.
- Incident events published by Alert Module.
- Authenticated SSE connections.
- User/application authorization context.

## Responsibility
1. Maintain authenticated SSE connections.
2. Associate each connection with the authenticated user.
3. Determine the applications the user may view.
4. Apply application/environment filtering server-side.
5. Stream normalized log events to authorized users.
6. Stream Incident events to authorized users.
7. Handle client disconnect/reconnect.
8. Apply server-side batching/throttling so high log rates do not overwhelm browsers.
9. Use event types such as:
   - `log`
   - `incident.created`
   - `incident.updated`
   - `incident.resolved`

## Output
SSE events to authorized dashboard clients.

Example:
```text
event: log
data: {...}
```

## Dependencies
- RabbitMQ
- Identity/authorization service contract
- Spring SSE/Web MVC or WebFlux
- Frontend clients

## Error handling
- Unauthorized connection -> reject.
- User loses access -> stop sending newly unauthorized events.
- Client disconnect -> release resources; client reconnects.
- Internal consumer failure -> reconnect/recover from RabbitMQ.
- Slow browser/client -> buffer within bounded limits; never allow unbounded memory growth.

## Must NOT do
- Must not be the source of truth for logs.
- Must not perform alert threshold calculation.
- Must not write every streamed event to PostgreSQL.
- Must not trust client-provided application permissions.
- Must not require SSE availability for log ingestion/storage.
