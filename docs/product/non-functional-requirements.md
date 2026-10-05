# Non-Functional Requirements

## NFR01 — Throughput

- Target sustained ingestion: **1,000 logs/second**.
- The required demo is defined in [MVP Scope](mvp-scope.md); benchmark coverage is defined in [Testing Strategy](../quality/testing.md).

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
