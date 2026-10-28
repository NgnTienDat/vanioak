# RabbitMQ Event Contracts v1

## Common envelope

All RabbitMQ domain events use:

```json
{
  "event_id": "0f5f3e9d-...",
  "event_type": "processed.log",
  "occurred_at": "2026-10-02T08:01:22.150Z",
  "producer": "processing",
  "schema_version": 1,
  "payload": {}
}
```

For single-log lifecycle events, `event_id` is the stable logical log identifier and is reused across retries/redeliveries. For a raw batch message, the envelope identifies the batch/message while each log in `payload.logs` carries its own stable `event_id`.

## 1. Raw log batch

Routing:

```text
raw.exchange -- raw.log --> raw.queue
```

Single-log ingestion may be normalized to the same structure with one item in `logs`.

Payload:

```json
{
  "request_id": "2bf6e2c8-...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "received_at": "2026-10-02T08:01:22.150Z",
  "logs": [
    {
      "event_id": "e6...",
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

Identity supplies the authenticated application/environment UUIDs. Processing copies these IDs and the batch's ingestion-time `received_at` to each stored log; retries preserve them. The envelope `event_type` is `raw.log`.

## 2. Processed log event

Routing:

```text
processed.exchange -- processed.log --> realtime.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "host_ip": "10.10.20.15",
  "level": "ERROR",
  "message": "Database connection timeout",
  "timestamp": "2026-10-02T08:01:22.123Z",
  "trace_id": "abc-123",
  "metadata": {},
  "received_at": "2026-10-02T08:01:22.150Z",
  "processed_at": "2026-10-02T08:01:22.310Z"
}
```

The envelope `event_type` is `processed.log`.

## 3. Critical event

Routing:

```text
critical.exchange -- critical.log --> alert.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "application_id": "a1...",
  "environment_id": "e3...",
  "level": "ERROR",
  "message": "Database connection timeout",
  "timestamp": "2026-10-02T08:01:22.123Z",
  "trace_id": "abc-123",
  "received_at": "2026-10-02T08:01:22.150Z"
}
```

Processing publishes this event only for `ERROR` and `CRITICAL`, with envelope `event_type: critical.log`. `received_at` is required and copied unchanged from the raw batch, including on retries; its use is defined in [Alert Module](../modules/04-alert.md#deterministic-semantics). The Alert Module computes the incident fingerprint from the critical event; Processing does not own fingerprint generation.

## 4. Invalid log / DLQ event

Routing:

```text
dead-letter.exchange -- dead-letter.log --> dead-letter.queue
```

Payload:

```json
{
  "event_id": "e6...",
  "reason": "MISSING_REQUIRED_FIELD",
  "field": "timestamp",
  "original_message": {},
  "failed_at": "2026-10-02T08:01:22.200Z"
}
```

Permanent/data errors go to DLQ with envelope `event_type: dead-letter.log`. Transient infrastructure errors use the retry path first.

For Processing whole-batch failures, retain these five payload keys and the common envelope:

- Envelope and payload `event_id` identify the raw batch: use its valid envelope UUID, otherwise a valid AMQP message ID. If neither exists, generate one DLQ accounting UUID for that transfer attempt. Do not replace per-log IDs inside the original batch.
- `reason` is `INVALID_RAW_BATCH`, `INVALID_RETRY_METADATA` or `PROCESSING_RETRY_EXHAUSTED`; `field` is `$`, identifying the whole batch. Processing does not recover or persist a valid prefix of an invalid batch.
- `original_message` is the complete original JSON object when parseable as one document, preserving numeric precision. Malformed or non-object input uses `{"body_base64":"<base64 of the exact original bytes>"}` instead. Do not truncate the body.
- Producer is `processing`, schema version is `1`, and envelope `occurred_at` and payload `failed_at` use the same failure-time instant. Original log and ingestion timestamps remain inside `original_message` unchanged.

Retry/DLQ copies may be duplicated after uncertain confirmation or a crash before the original ACK. Generated fallback DLQ IDs identify transfer attempts, not a new logical log.

### Alert critical-event failure accounting

These rules apply only to Alert handling critical.log from alert.queue. Existing
common, Processing and Realtime contracts are unchanged.

- Use integer AMQP x-alert-retry-count: absent is zero; retries after the initial
  attempt carry 1, 2, 3 with default minimum delays 5s, 30s, 120s. Configured bound
  equals the delay-list length; zero requires an empty list. Byte/short/int/long
  counters within bounds are accepted; other types, negative/out-of-range values
  use INVALID_RETRY_METADATA. Never infer attempts from redelivered or x-death.
- Retry copies preserve exact original body and message_id, use persistent mandatory
  publication through the default exchange to alert.retry.queue, and carry only
  bounded transport properties and the new retry counter/expiration. Reuse event_id
  and received_at without substituting retry time. At exhaustion, use the existing
  DLQ route with ALERT_RETRY_EXHAUSTED. Missing/disabled rule is durable accounting,
  not failure. A committed duplicate is success without repeat Incident changes.
- Permanent critical schema/identity violations use ALERT_INVALID_CRITICAL_EVENT;
  unsupported integral versions of an otherwise complete common critical envelope
  use ALERT_UNSUPPORTED_SCHEMA_VERSION without interpreting unknown payloads as v1.
  Missing/wrong-type versions are invalid. Preserve the common six envelope keys
  and five DLQ payload keys: producer alert, event_type dead-letter.log, version1;
  field is a safe fixed path or $. Select valid original envelope UUID, else valid
  AMQP message_id, else at most one fallback UUID per source handling. Reuse selected
  identity/body/failure instant throughout that handling. Fallback is not guaranteed
  stable across redelivery/restart; original IDs inside original_message stay intact.
- Complete unambiguous JSON objects preserve all fields/values semantically, including
  unknown fields/arbitrary-precision numbers; serialization may change formatting.
  Malformed/non-object/duplicate-key/invalid-UTF8 input uses body_base64 with exact
  source bytes. Do not truncate or claim byte-identical semantic JSON round trips.
- One failure handoff publication per source handling. Persistent mandatory positive
  native confirm AND no return is required before individual source ACK. Return
  overrides ACK. NACK/timeout/unknown correlation/connection or send failure never
  authorizes source ACK. No publication hot retry, source hot requeue, or tag replay
  on a replacement channel. Uncertain accepted transfers may duplicate copies.
- One native publication slot remains owned until terminal native outcome or proven
  physical retirement of the owned channel with pending confirms cleared. Caller
  cancellation/timeout does not release it or cancel the private correlation. Never
  reset shared connections or replace a still-blocked native publisher worker.
- Initial exact input budget is 1,048,576 bytes and complete lossless DLQ JSON budget
  is 2,097,152 bytes. Strict UTF8/one-document/duplicate detection; parser bounds are
  depth64, tokens262144, names4096, numeric tokens4096, strings1048576. Construct full
  output through a capped sink, not an unrestricted serialize-then-check operation.
  These are not hard heap caps or an upstream accepted-size invariant.
- Schema-valid oversized input, unclassifiable parser/capacity work, fidelity/output
  overflow or size incompatibility must remain unACKed: no truncation, silent drop,
  fake malformed reason or partial publication. Latch Alert not-ready and stop only
  its consumption. A single valid oversized event may stop Alert; shared-broker
  backlog may affect other publishers. This is an accepted availability limitation
  of this policy, not automatic oversized-event support or resource isolation.
- Failed/uncertain handoff, ACK uncertainty or source-channel failure also latches
  stopped readiness. Automatic listener/process restart must not clear the condition:
  a forced CREATE_NEW Alert startup marker in an operator-reviewed stable absolute
  path is required before consumption and remains after any shutdown. Existing or
  unusable marker refuses startup. No alternate-path bypass or automatic removal.
  Recovery requires separate operator review, verified work disposition and preserved
  evidence; ACK/purge/delete/skip/manual replay must not conceal blocking work.
- Consumer cancellation does not itself requeue in-flight deliveries. Closing the
  owning channel permits normal broker redelivery of unacknowledged work. Source ACK
  after successful Engine evaluation occurs only after its PostgreSQL commit and
  never depends on best-effort Realtime or Telegram delivery. Fatal VM/native faults
  and real close/redelivery behavior require separate verification.

### Realtime processed-event quarantine

The following rules apply only to Realtime handling of `processed.log` from
`realtime.queue`; the Processing whole-batch rules above are unchanged.
The absence of an upstream size invariant is accepted as an MVP availability
limitation under the fail-closed Realtime capacity-pause policy below. It is not
a guarantee of automatic oversized-event support. Consumer implementation,
native verification and activation remain separate approval gates; this section
does not activate a worker or change Ingestion/Processing size contracts.

- A valid supported event becomes source-ACK-ready after bounded fan-out,
  no-subscriber no-op or explicit slow-client-close accounting. Browser receipt
  is not required. Authorization/socket failures do not make a source malformed.
- Established schema violations use `REALTIME_INVALID_PROCESSED_EVENT`. A complete
  otherwise valid common envelope identifying `processed.log` with an unsupported
  integral schema version uses `REALTIME_UNSUPPORTED_SCHEMA_VERSION`; do not
  validate unknown-version payloads as v1. Missing/wrong-type versions are schema
  violations. Valid oversized events are not schema violations.
- Reuse exactly the common six envelope keys and five DLQ payload keys above.
  Envelope `event_type` is `dead-letter.log`, producer is `realtime`, and version
  is integer `1`. Select a canonical valid original envelope UUID, else a valid
  AMQP message ID, else at most one fallback UUID per source-delivery handling.
  Extract an envelope ID only from a complete unambiguous supported JSON object.
  Reuse that selected envelope/payload/message ID for all bounded attempts and
  preserve IDs inside the original message. A fallback is an accounting ID and
  is not guaranteed stable across broker redeliveries or process restarts.
- Freeze reason, a bounded fixed validator `field` path (or `$`), original
  representation and the identical `occurred_at`/`failed_at` instant for a
  handling. Retries reuse the same serialized body but have distinct native
  publication correlations. Do not put rejected values in diagnostics or paths.
- Complete supported JSON objects retain every field/value, including unknown
  fields and arbitrary-precision numbers, without truncation or rounding. This
  is semantic JSON preservation: formatting, field order, escape spelling and
  equivalent numeric notation may change; byte-identical JSON is not promised.
  Require strict UTF-8, one document and duplicate-key detection. Malformed JSON,
  invalid UTF-8, duplicate-key objects or non-object input use the existing
  `{"body_base64":"<base64 of exact source bytes>"}` representation. An already
  classifiable invalid/unsupported event may also use that lossless wrapper when
  semantic-object fidelity cannot be established. Parser resource exhaustion
  alone does not establish a schema violation; an unclassifiable source must be
  retained without ACK or an invented reason.
- Publish persistent, mandatory JSON to the existing `dead-letter.exchange`
  using `dead-letter.log`. Source ACK requires its own positively confirmed,
  non-returned native handoff. A mandatory return overrides a positive confirm.
  The installed adapter must resolve return/confirm ordering before reporting
  routed success; an ACK flag or a completed caller future alone is insufficient.
  Source ACK is individual and belongs to the original source channel/owner.
- NACK, mandatory return, timeout, send/connection failure or uncertain
  correlation never makes the source ACK-ready. Admit at most three attempts
  per handling, each with a five-second confirm deadline and five-second delay
  between admitted attempts. Set a monotonic confirm deadline on admission,
  before send; later waits consume the remaining budget rather than resetting it.
  The 25-second confirm/backoff schedule is not a
  bound on blocked send, serialization or native cleanup. Stop earlier if native
  ownership cannot be resolved safely.
- Serialize Realtime DLQ publication with one owned outstanding native slot,
  including across recovery continuations. Admit a subsequent attempt/handling
  only after terminal native completion is reconciled or the dedicated owned
  publication channel is physically retired and pending-confirm ownership is
  demonstrably cleared. Caller timeout/cancellation must not free the slot or
  manufacture completion. Unresolved cleanup holds the slot and stops Realtime;
  never reset shared connections or retire another module's channel.
- On exhaustion or unresolved ownership, latch Realtime not-ready/stopped so
  automatic listener recovery cannot hot-restart it. Stop only its consumption
  and close affected streams through their bounded lifecycle. Cancellation alone
  does not requeue unacknowledged deliveries: they remain owned by the source
  channel. Actual source-channel/connection closure permits normal broker
  redelivery. Do not manually replay, hot-requeue, add a retry queue, reuse old
  delivery tags on new channels, or ACK unaccounted work. Resume requires a
  separately reviewed operator action.
- A schema-valid intrinsically oversized event, an above-cap input which cannot
  safely be classified, parser resource exhaustion without an established schema
  violation, or a lossless DLQ representation that cannot fit within construction/
  publication bounds enters capacity pause. Publish nothing for that unaccounted
  delivery and do not ACK, truncate, silently discard, skip or hot-requeue it.
  Such valid/unknown work is not `REALTIME_INVALID_PROCESSED_EVENT`. Ordinary
  per-client buffer overflow remains bounded slow-client close accounting and
  does not itself constitute intrinsic event capacity failure.
- Capacity pause latches Realtime readiness false, stops only its consumption and
  closes/marks its SSE subscriptions unavailable through the bounded lifecycle.
  Automatic listener recovery/restart must not clear the latch or repeatedly
  consume the blocking event. Automatic process restart must also respect a
  reviewed startup interlock or durable/operator-managed fence; a volatile flag
  alone is not proof. This is an explicit exception to generic reconnect behavior.
- Canceling the consumer does not itself requeue in-flight unacknowledged work.
  Preserve source channel ownership until actual own-channel/connection closure
  permits normal broker redelivery. Keep other modules' channels/connections
  untouched. Resume requires an explicitly reviewed operator recovery procedure,
  with a remedy for the blocking work, verified scope/counts and explicit fence
  clearance; do not ACK, purge, delete, skip or manually republish the blocking
  work as an undocumented recovery action. Do not reuse old delivery tags on a
  new channel. Already-accepted pending work must remain accounted and retained.
- A single valid oversized event may stop the Realtime stream. Its retained
  backlog on the shared broker may eventually affect other publishers through
  shared disk/memory pressure. Capacity pause is not absolute resource isolation,
  unbounded broker capacity or automatic support for arbitrary upstream sizes.

Initial Realtime serialization budgets are 1,048,576 exact input body bytes,
1,048,576 complete serialized UTF-8 SSE-frame bytes including event/data framing,
and 2,097,152 bytes for the complete DLQ JSON body. The nine-field log frame adds
17 framing bytes to compact JSON; the longest Incident event name adds 31.
Do not treat a JSON-only size check as a complete-frame check. Full-frame sizing
must complete within bounded construction before fan-out/source ACK.

Parse supported input with strict UTF-8 and one-document/duplicate-key checks,
with bounds no looser than depth 64, 262,144 tokens, 4,096-character names,
4,096-character numeric tokens and 1,048,576-character strings, plus the exact
input byte cap. Numeric values must retain arbitrary precision. Resource-limit
failure is a capacity classification unless schema invalidity is independently
established; do not infer invalidity merely from a parser refusing a large value.

Serialize the whole six-key/five-field DLQ envelope into a byte-counting capped
sink. All JSON punctuation, escaped UTF-8 values and the complete original object
or exact-byte base64 wrapper count toward 2 MiB. No partial/failed serialization
may be published. Do not first allocate arbitrary output with an unbounded
serializer and check its size afterward. Fixed metadata uses canonical UUIDs,
the two approved reasons, fixed safe validator paths at most 64 ASCII characters
and canonical failure Instants at most 37 ASCII bytes. For base64 input of N
bytes, `4*ceil(N/3)+1024` conservatively covers the complete body under those
metadata bounds; at 1 MiB input that is 1,399,128 bytes. Semantic-object encoding
may expand: it must pass the same hard output cap or enter capacity pause without
truncation/ACK. These byte/parser bounds are not a hard heap allocation guarantee.
Use fixed bounded AMQP properties rather than arbitrary copied source headers.

Before activation and after fixture/configuration changes, read the actual
broker message-size ceiling and require compatibility with the configured DLQ
body budget; no default may be assumed. Serialization checks and a compatible
configured ceiling do not prove native publication, return/confirm behavior or
transport availability. If the complete lossless representation cannot fit or
be constructed faithfully, or publication size compatibility cannot be
established, keep Realtime not-ready/capacity-paused without publishing or ACKing
unaccounted work. Existing bounded native-slot/transfer-failure rules still apply
when a completed in-budget body cannot be safely confirmed.

An uncertain publication can already be broker-accepted; retry/redelivery may
duplicate DLQ copies. If the source ACK becomes uncertain after confirmed DLQ
transfer, stop and preserve uncertainty rather than retry a delivery tag on a
replacement channel. No exactly-once or cross-redelivery fallback-ID guarantee
is introduced.

## 5. Incident event for Realtime

Alert emits an internal event to Realtime using the common envelope:

```json
{
  "event_id": "i1-event...",
  "event_type": "incident.created",
  "occurred_at": "2026-10-02T08:01:25Z",
  "producer": "alert",
  "schema_version": 1,
  "payload": {
    "incident_id": "i1...",
    "application_id": "a1...",
    "environment_id": "e3...",
    "level": "ERROR",
    "status": "OPEN",
    "message": "Database connection timeout",
    "error_count": 10
  }
}
```

Lifecycle event types:

All lifecycle payloads carry `incident_id`, `application_id`, `environment_id`, and `level` for authorization/filtering. Realtime may project these into the SSE payloads defined in `sse-api.md`.

- `incident.created`: `status: OPEN`, `message`, `error_count` (shown above).
- `incident.updated`: `status: OPEN`, `error_count`, `last_seen_at`.
- `incident.resolved`: `status: RESOLVED`, `resolved_at`.
