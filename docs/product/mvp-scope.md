# MVP Scope

## 1. Goal

Build a lightweight centralized log monitoring and alerting system for **dev/test/staging** environments, focused on:

1. collecting JSON logs from multiple applications/services;
2. searching centralized logs;
3. viewing logs in realtime;
4. detecting ERROR/CRITICAL incidents;
5. notifying engineers without alert flooding.

Performance targets and delivery semantics are defined in [Non-Functional Requirements](non-functional-requirements.md).

## 2. In Scope

### Application & Access Management
- Admin manages applications and environments.
- Admin assigns Engineers to applications.
- Engineer access is application-scoped and includes all environments of the assigned application.
- API keys are created/revoked/rotated and scoped to one environment.

### Log Ingestion
- HTTP POST single/batch JSON ingestion.
- API-key authentication.
- Structured logs with application/environment and source-host context; see FR02 in [Functional Requirements](functional-requirements.md).

### Log Processing & Storage
- Validate and normalize raw logs.
- Asynchronous log persistence.
- ERROR/CRITICAL events are copied to the alert path.
- Failure recovery without silent loss; see [Failure Handling](../architecture/failure-handling.md).

### Search
Combined log search filters as defined in FR04 of [Functional Requirements](functional-requirements.md).

Cursor pagination is required.

### Realtime
- SSE Live View.
- Application/environment filtering.
- Server-side authorization.
- Incident realtime events.

### Alerting
- Rules configured by application/environment/level.
- Threshold + time window + cooldown.
- Incident-oriented alerting.
- Telegram notification.
- Persisted Incidents.

### Retention
- All ClickHouse logs older than 7 days are removed.
- No level-specific retention in MVP.

### Analytics
Basic Application Health Analytics:
- total logs;
- ERROR/CRITICAL counts;
- error rate;

### Packaging & Demo
- Dockerfile(s).
- Docker Compose for Backend, Frontend, PostgreSQL, ClickHouse, RabbitMQ and Redis.
- Demo log generator.
- Demonstrate 500 logs within 2 seconds and realtime display.

## 3. Out of Scope

Not part of MVP:

- AI log analysis/classification;
- Elasticsearch/OpenSearch;
- Kafka;
- Kubernetes;
- production/multi-region HA design;
- exactly-once delivery;
- local durable disk spool at Ingestion;
- full APM/tracing platform;
- infrastructure/host monitoring;  
- automatic root-cause analysis;
- advanced reporting/BI;
- complex multi-tenant SaaS billing/organization model.

## 4. Future Extension Points

Future AI/additional analytics consumers may subscribe to normalized events independently; see NFR12 in [Non-Functional Requirements](non-functional-requirements.md).

Future features must not be placed in the ingestion critical path unless the architecture is explicitly revised.

## 5. MVP Completion

MVP is complete when the requirements in  `../quality/acceptance-criteria.md` pass and the required 500-logs/2-seconds demo works end-to-end.
