# Module Implementation Rules for Coding Agents

## Backend repository structure

This repository is a monorepo.

```text
vanioak/
├── backend/
├── frontend/
├── docs/
├── .agents/
└── AGENTS.md
```

Backend implementation belongs under `backend/`.

Do not inspect or modify `frontend/` unless the task explicitly includes frontend work.

## Spring Boot module structure

Organize backend code under the application's existing base package:

```text
backend/src/main/java/<base-package>/
├── api/
│   ├── identity/
│   ├── ingestion/
│   ├── analysis/
│   ├── alert/
│   └── realtime/
│
├── modules/
│   ├── identity/
│   │   ├── api/
│   │   └── internal/
│   ├── ingestion/
│   │   ├── api/
│   │   └── internal/
│   ├── processing/
│   │   ├── api/
│   │   └── internal/
│   ├── alert/
│   │   ├── api/
│   │   └── internal/
│   ├── realtime/
│   │   ├── api/
│   │   └── internal/
│   ├── analysis/
│   │   ├── api/
│   │   └── internal/
│   └── retention/
│       ├── api/
│       └── internal/
│
└── common/
```

Rules:

- Top-level `api/` contains external HTTP/SSE adapters such as controllers, transport DTOs, request validation, and response/error mapping.
- `modules/<module>/api/` contains the module's small public Java contract: interfaces/facades and cross-module DTO/value types that other modules are allowed to use.
- `modules/<module>/internal/` contains implementation details owned by that module, such as services, repositories, persistence entities, workers/consumers, caches, and external clients.
- `common/` contains technical cross-cutting code only, not business logic owned by a module.
- A module may create subpackages inside `internal/` only when useful, for example `service/`, `repository/`, `entity/`, `messaging/`, `cache/`, or `client/`.
- Do not create empty layers or packages only for symmetry.
- When bootstrapping the backend, determine the actual base package from repository configuration/source layout; do not invent one.

## Internal communication

Because this is a modular monolith:
- Prefer direct in-process service interfaces between modules.
- Cross-module synchronous calls must use the target module's public contract under `modules/<module>/api/`.
- Other modules must not import `modules/<module>/internal/**`.
- Do not access another module's repository, persistence entity, cache, or infrastructure implementation directly.
- Do not create localhost HTTP calls between modules.
- Use RabbitMQ for asynchronous/event-driven boundaries where specified.
- Keep public module interfaces explicit and small.

## Persistence ownership

- Identity owns PostgreSQL user/application/environment/credential/access data.
- Alert owns PostgreSQL alert rule/incident/notification state.
- Processing owns writes to ClickHouse logs.
- Analysis reads ClickHouse.
- Retention manages ClickHouse retention.

## Module Exceptions

- Each module defines its business exception in `modules/<module>/api/`, for example `IdentityException`.
- Module exceptions extend `RuntimeException` and expose a module-specific `ErrorCode`.
- Each module-specific `ErrorCode` directly defines the Spring `HttpStatus` and client-safe message; it is the single source of business error metadata.
- Services throw module exceptions with the corresponding `ErrorCode`, without creating client-facing messages in services; controllers do not catch them manually.
- Each HTTP API area may provide a module-specific `@RestControllerAdvice` under `api/<module>/`.
- Module exception handlers take status and message directly from `ErrorCode` and return the shared `ApiResponse<T>` envelope with `success: false` and `data: null`.
- Validation errors and unexpected exceptions are handled by the global exception handler.
- Unexpected `500` responses must use a safe generic message and must not expose internal exception details or stack traces.
- Field validation returns `400`, message `Validation failed`, and a field-to-message map in `data` (empty when there are no field errors), without rejected values.
- Log unexpected exception types and stack traces server-side without request bodies, exception messages that may contain secrets, credentials, tokens, or passwords.
- Do not introduce a shared business-exception hierarchy unless multiple modules have a demonstrated need for it.

## Database migrations

- Manage PostgreSQL schema changes with Flyway migrations under `backend/src/main/resources/db/migration/`.
- Do not rely on Hibernate `ddl-auto` to create or mutate the PostgreSQL schema.
- Keep Flyway migrations consistent with `docs/contracts/postgres-schema.sql`.
- Never edit an already-applied migration; add a new versioned migration instead.
- Schema changes must preserve module data ownership.

## No hidden cross-module writes

A module must not directly write another module's tables. Use that module's public interface when synchronous coordination is required.

## Event identifiers

`event_id` is generated once at ingestion and remains unchanged through retries/redelivery. It is the logical identifier for idempotency.

## Response contract

All REST responses, including errors, must use the common `success`, `message`, `data` envelope defined in [OpenAPI](../contracts/openapi.yaml). Error details belong under `data`, as explained in [REST API](../contracts/rest-api.md).

## Scope guard

Do not introduce Kafka, Elasticsearch, Kubernetes, microservices, or AI processing into the MVP unless a documented architecture decision explicitly changes the scope.
