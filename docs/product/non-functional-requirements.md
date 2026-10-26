# Non-Functional Requirements

## NFR01 — Throughput

- Target sustained ingestion: **1,000 logs/second**.
- The required demo is defined in [MVP Scope](mvp-scope.md); benchmark coverage is defined in [Testing Strategy](../quality/testing.md).

### Sustained Pipeline Performance Validation

Separately validate **Ingestion → RabbitMQ → Processing → ClickHouse** under a documented benchmark workload:

- Sustain **at least 1,000 accepted logs/second** and **at least 1,000 searchable distinct logs/second** over the same measurement window. Successful pipeline throughput counts distinct stable `event_id` values first observed persisted and queryable in ClickHouse, not HTTP `202` responses, physical row copies or downstream publication counts.
- RabbitMQ raw backlog must remain stable without sustained growth. Record ready and unacknowledged counts and account for the number of logs per raw message.
- After a bounded drain, reconcile all accepted log IDs with searchable ClickHouse records, deduplicating by stable `event_id`. At-least-once delivery remains unchanged; this is not an exactly-once guarantee.
- Report p50/p95/p99 latency from ingestion `received_at` to ClickHouse query visibility. No fixed end-to-end latency SLA is defined; NFR02 remains the separate HTTP-to-RabbitMQ-confirm target.
- Keep required processed/critical publishing enabled. Its effect on raw ACK timing and backlog is part of the real pipeline, but downstream publication is not the primary throughput metric. Realtime and Alert consumer performance is outside this validation.

Use a **proposed 5-minute sustained benchmark**, with the chosen duration, workload, batch sizes, severity mix, hardware, resource limits, drain deadline and stop conditions explicitly recorded according to [Testing Strategy](../quality/testing.md#sustained-pipeline-benchmark).

---

## NFR02 — Ingestion Latency

Under normal healthy conditions:

- Ingestion API target: **p95 < 100 ms**.
- Latency is measured until the log is confirmed by Message Queue, not until it is persisted to Database.

---

## NFR03 — Alert Latency

Under normal healthy conditions:

- Target from acceptance of the log that reaches the rule threshold to an eligible alert/Incident notification: **< 3 seconds**, subject to cooldown.

---

## NFR04 — Search Latency

For normal search queries over the 7-day retention window:

- Target: **p95 < 1 second**.

---

## NFR05 — Delivery Semantics

- Delivery model: **At-least-once**.
- Duplicate messages are acceptable.
- Processing must tolerate redelivery/duplicate delivery.

---

## NFR06 — Availability

- Architectural target: **99.9% availability** when deployed with appropriate redundancy.
- MVP dev/test deployment using single-instance Docker Compose is **not an SLA commitment for 99.9%**.

---

## NFR07 — Scalability

The Modular Monolith backend should allow horizontal scaling and configurable Log/Alert Worker concurrency within the same application.

Message Queue must decouple producer traffic from processing capacity.

---

## NFR08 — Reliability / Backpressure

When downstream capacity is exhausted:

```text
MQ healthy
  -> accept logs

MQ near capacity
  -> expose health/pressure signal

MQ full/unavailable
  -> reject new ingestion with 429/503
  -> producer retries with backoff
```

The system must not return success for a log that was never accepted by the Message Queue.

---

## NFR09 — Security

Authentication, authorization, API-key lifecycle, and secret handling must follow [Security Specification](../quality/security.md). Credential-cache behavior is defined in [Ingestion Module](../modules/02-ingestion.md).

---

## NFR10 — Browser Performance

The UI must not attempt one DOM/render update for every incoming log at high throughput.

The live stream should use buffering/batching/throttling so the browser remains usable during bursts.

---

## NFR11 — Retention

Retention follows FR08 in [Functional Requirements](functional-requirements.md).

---

## NFR12 — Extensibility

The MVP must leave an extension point for future AI analysis without placing AI on the critical ingestion path.

Future AI processing should be able to consume log/incident events independently.
