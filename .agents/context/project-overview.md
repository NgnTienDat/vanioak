# Project Overview

## Purpose

Centralized log monitoring and alerting for dev/test/staging environments.
Follow `docs/product/mvp-scope.md` for MVP boundaries and
`docs/product/functional-requirements.md` for required capabilities.

## System Orientation

- Backend: Java + Spring Boot Modular Monolith.
- Frontend: React + TypeScript.
- Infrastructure: PostgreSQL, ClickHouse, Redis, and RabbitMQ.
- Synchronous module calls use in-process interfaces.
- RabbitMQ carries asynchronous log/event flows with at-least-once delivery.
- ClickHouse stores searchable logs and supplies analytics data.
- PostgreSQL stores relational, configuration, and Incident state.
- Redis holds operational cache/dedup state, not authoritative persistent data.
- Follow `docs/architecture/architecture.md` for architectural invariants.

## Logical Modules and Capabilities

- Identity: users, applications/environments, API keys, and application-scoped access.
- Ingestion: single/batch JSON log submission.
- Processing: normalization, persistence, and downstream log events.
- Alert: ERROR/CRITICAL Incidents and Telegram notifications.
- Realtime: authorized SSE live logs and Incident events.
- Analysis: application health analytics; centralized log search uses ClickHouse.
- Retention: the MVP's 7-day log retention.
- Module behavior and dependencies are defined under `docs/modules/`.

## Documentation Map

- `docs/product/`: scope, functional requirements, NFRs, and use cases.
- `docs/architecture/`: structure, flows, RabbitMQ topology, and failures.
- `docs/modules/`: module behavior, boundaries, and implementation conventions.
- `docs/contracts/`: exact REST/SSE/event contracts and database schemas.
- `docs/quality/`: acceptance, testing, and security.
- `docs/operations/`: runtime configuration.

This file is orientation only. When a normative document exists, follow the
normative document rather than this summary.
