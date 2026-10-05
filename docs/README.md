# Documentation Guide

`docs/` contains authoritative project specifications; `.agents/` contains
instructions for how coding agents work. Use this guide to select documents
relevant to the current task. Do not read every document by default.
Links below are relative to this file.

[DETAI.md](../DETAI.md) is the original assignment/reference. Current normative implementation behavior lives under `docs/`; where the refined MVP differs from the original wording, follow the owning normative document here.

## 1. Documentation Areas

| Area | Purpose |
|---|---|
| [product/](product/) | MVP scope, functional requirements, NFRs, use cases |
| [architecture/](architecture/) | System structure, flows, RabbitMQ, failures |
| [modules/](modules/) | Per-module behavior and boundaries |
| [contracts/](contracts/) | Exact REST, SSE, event, and database contracts |
| [quality/](quality/) | Acceptance, testing, security |
| [operations/](operations/) | Runtime configuration |

For module orientation only, use [Module Overview](modules/00-overview.md).

## 2. Source of Truth

| Information | Authoritative source |
|---|---|
| MVP boundaries | [MVP Scope](product/mvp-scope.md) |
| Functional behavior | [Functional Requirements](product/functional-requirements.md) |
| Non-functional targets | [Non-Functional Requirements](product/non-functional-requirements.md) |
| User journeys/use cases | [Use Cases](product/use-cases.md) |
| Overall architecture | [Architecture](architecture/architecture.md) |
| End-to-end flows | [Data Flow](architecture/data-flow.md) |
| RabbitMQ topology, routing, ACK, retry, DLQ | [RabbitMQ Topology](architecture/rabbitmq-topology.md) |
| Failure policy | [Failure Handling](architecture/failure-handling.md) |
| Per-module behavior | [Identity](modules/01-identity.md), [Ingestion](modules/02-ingestion.md), [Processing](modules/03-processing.md), [Alert](modules/04-alert.md), [Realtime](modules/05-realtime.md), [Analysis](modules/06-analysis.md), [Retention](modules/07-retention.md) |
| Module dependencies | [Dependency Matrix](modules/dependency-matrix.md) |
| Implementation conventions | [Conventions](modules/conventions.md) |
| Exact REST API contract | [OpenAPI](contracts/openapi.yaml) |
| REST usage/explanation | [REST Guide](contracts/rest-api.md) |
| Exact SSE contract | [SSE Contract](contracts/sse-api.md) |
| Exact RabbitMQ event shapes | [Event Contracts](contracts/event-contracts.md) |
| Exact PostgreSQL schema | [PostgreSQL Schema](contracts/postgres-schema.sql) |
| Exact ClickHouse schema | [ClickHouse Schema](contracts/clickhouse-schema.sql) |
| Schema rationale | [Schema Notes](contracts/schema-notes.md) |
| Acceptance criteria | [Acceptance Criteria](quality/acceptance-criteria.md) |
| Testing strategy | [Testing Strategy](quality/testing.md) |
| Security rules | [Security](quality/security.md) |
| Runtime configuration | [Configuration](operations/configuration.md) |

When documents conflict, prefer the file that owns that type of information.
Contracts own exact machine/interface shapes. Higher-level requirements define
required behavior; lower-level docs must implement them consistently.
Resolve conflicts by ownership, not a universal document hierarchy.

## 3. Task-to-Document Routing

Read the relevant sections of the sources below, not necessarily whole files.

| Task | Read |
|---|---|
| Understand MVP/feature scope | [MVP Scope](product/mvp-scope.md), relevant [FR](product/functional-requirements.md) |
| Identity/authentication/authorization | [Identity](modules/01-identity.md), [Security](quality/security.md), [PostgreSQL](contracts/postgres-schema.sql), relevant [OpenAPI](contracts/openapi.yaml) |
| Ingestion | [Ingestion](modules/02-ingestion.md), [OpenAPI](contracts/openapi.yaml), [Events](contracts/event-contracts.md), [Topology](architecture/rabbitmq-topology.md), [Failures](architecture/failure-handling.md) |
| Processing | [Processing](modules/03-processing.md), [Flows](architecture/data-flow.md), [Topology](architecture/rabbitmq-topology.md), [Events](contracts/event-contracts.md), [ClickHouse](contracts/clickhouse-schema.sql) |
| Alerting/incidents | [Alert](modules/04-alert.md), [Events](contracts/event-contracts.md), [PostgreSQL](contracts/postgres-schema.sql); [Security](quality/security.md) when authorization is involved |
| Realtime/SSE | [Realtime](modules/05-realtime.md), [SSE](contracts/sse-api.md), [Security](quality/security.md) |
| Log Search | [Analysis](modules/06-analysis.md), FR04 in [FR](product/functional-requirements.md), search operation in [OpenAPI](contracts/openapi.yaml), [REST Guide](contracts/rest-api.md), [Security](quality/security.md), [ClickHouse](contracts/clickhouse-schema.sql) |
| Analytics | [Analysis](modules/06-analysis.md), analytics sections of [OpenAPI](contracts/openapi.yaml), [ClickHouse](contracts/clickhouse-schema.sql) |
| Retention | [Retention](modules/07-retention.md), [ClickHouse](contracts/clickhouse-schema.sql), FR08 in [FR](product/functional-requirements.md) |
| REST API change | [OpenAPI](contracts/openapi.yaml), [REST Guide](contracts/rest-api.md), owning [module](modules/), relevant [FR](product/functional-requirements.md) |
| Database change | Relevant [PostgreSQL](contracts/postgres-schema.sql)/[ClickHouse](contracts/clickhouse-schema.sql) contract, [Schema Notes](contracts/schema-notes.md), owning [module](modules/) |
| RabbitMQ/event change | [Topology](architecture/rabbitmq-topology.md), [Events](contracts/event-contracts.md), affected [modules](modules/), [Failures](architecture/failure-handling.md) |
| Security-sensitive change | [Security](quality/security.md), owning [module](modules/), relevant [REST](contracts/openapi.yaml)/[SSE](contracts/sse-api.md) contract |
| Tests/acceptance | [Testing](quality/testing.md), [Acceptance](quality/acceptance-criteria.md), relevant requirement/module/contract |
| Runtime configuration | [Configuration](operations/configuration.md), affected module/contract as needed |

## 4. Reading Rules

- Start from the owning requirement, module, or contract; expand only as needed.
- Read only documents relevant to the current task.
- Use API contracts for exact fields/status codes, SQL for database structure,
  and event contracts for payloads; do not infer these from prose or diagrams.
- Overview files do not override exact contracts for interface shape.
- Modify multiple sources of truth only when the requested change spans them.

## 5. Current Project Invariants

These are routing/sanity-check context; follow the owning documents for details.

- Spring Boot Modular Monolith.
- RabbitMQ for asynchronous log/event flows.
- PostgreSQL for relational/configuration/Incident state.
- ClickHouse for logs/analytics.
- Redis for cache/dedup/operational state.
- SSE for realtime delivery.
- At-least-once delivery.
- Application-scoped user access and environment-scoped API keys.

## 6. Agent Instructions

Repository-level agent behavior is defined in [AGENTS.md](../AGENTS.md) and
[.agents/](../.agents/). `docs/` defines the project, not the agent workflow.
If requested implementation conflicts with authoritative docs, stop and surface
the conflict instead of silently changing the design.
