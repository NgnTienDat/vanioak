# Module Implementation Rules for Coding Agents

## Spring Boot structure
Use module boundaries in the codebase. Recommended package-level separation:

```text
...identity
...ingestion
...processing
...alert
...realtime
...analysis
...retention
...common
```

## Internal communication
Because this is a modular monolith:
- Prefer direct in-process service interfaces between modules.
- Do not create localhost HTTP calls between modules.
- Use RabbitMQ for asynchronous/event-driven boundaries where specified.
- Keep module interfaces explicit and small.

## Persistence ownership
- Identity owns PostgreSQL user/application/environment/credential/access data.
- Alert owns PostgreSQL alert rule/incident/notification state.
- Processing owns writes to ClickHouse logs.
- Analysis reads ClickHouse.
- Retention manages ClickHouse retention.

## No hidden cross-module writes
A module must not directly write another module's tables. Use that module's service interface.

## Event identifiers
`event_id` is generated once at ingestion and remains unchanged through retries/redelivery. It is the logical identifier for idempotency.

## Response contract
All REST responses must use:

```json
{
  "success": true,
  "message": "...",
  "data": {}
}
```

or:

```json
{
  "success": false,
  "message": "...",
  "data": {
    "code": "...",
    "request_id": "...",
    "details": {}
  }
}
```

## Scope guard
Do not introduce Kafka, Elasticsearch, Kubernetes, microservices, or AI processing into the MVP unless a documented architecture decision explicitly changes the scope.
