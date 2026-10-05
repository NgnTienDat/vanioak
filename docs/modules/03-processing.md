# Processing Module

## Purpose
Consume raw logs, validate/normalize them, batch-write them to ClickHouse, and publish processed/critical events.

## Input
- Messages from `raw.queue`.

Raw message contains application-submitted log data plus ingestion metadata.

## Responsibility
1. Consume raw messages.
2. Validate required fields and allowed enum values.
3. Normalize timestamps, level values, application/environment identifiers, and metadata.
4. Build the canonical ClickHouse log model.
5. Buffer logs for batch insert:
   - flush at **1,000 logs**, or
   - flush after **2 seconds**, whichever comes first.
6. Insert normalized logs into ClickHouse.
7. Publish normalized events to `processed.exchange`.
8. For `ERROR` and `CRITICAL`, additionally publish an event to `critical.exchange`.
9. ACK the raw message only after required persistence/publish steps succeed.
10. Send permanently invalid messages to `dead-letter.exchange`.
11. Use retry path for transient ClickHouse/RabbitMQ failures.

## Output
### Processed event
Published to `processed.exchange` for Realtime consumers.

### Critical event
Published to `critical.exchange` when level is `ERROR` or `CRITICAL`.

### DLQ event
Published to `dead-letter.exchange` for permanent processing/data errors.

## Dependencies
- RabbitMQ
- ClickHouse
- Processing configuration
- Optional schema/validation library

## Error handling
### Permanent/data error
Classification follows [Failure Handling](../architecture/failure-handling.md).

Action:
- publish to DLQ
- ACK the original message after successful DLQ publication

### Transient error
Classification follows [Failure Handling](../architecture/failure-handling.md).

Action:
- do not ACK
- route through retry queue
- retry with bounded attempts
- move to DLQ after retry exhaustion

### Partial batch failure
- Treat the batch atomically from the module's perspective where possible.
- Do not acknowledge messages that cannot be safely accounted for.
- Use `event_id` as a stable identifier across retries.

## Must NOT do
- Must not authenticate human users.
- Must not query or enforce Engineer permissions.
- Must not send Telegram notifications.
- Must not open/resolve Incidents.
- Must not stream directly to browsers.
- Must not perform expensive analytics queries.
