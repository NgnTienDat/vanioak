# Architecture Guardrails

- Keep the backend one Spring Boot Modular Monolith with explicit logical modules.
- Do not introduce microservices or localhost HTTP calls between modules.
- Use in-process interfaces for synchronous module communication.
- Use RabbitMQ for asynchronous flows defined by the architecture.
- Follow module dependency boundaries; avoid circular dependencies.
- Access another module through its public API/interface, not its repositories, persistence entities, or infrastructure internals.
- Keep infrastructure code inside the module that owns its use.
- Preserve at-least-once delivery, stable event identity, and documented failure accounting.
- Do not introduce Kafka, Elasticsearch, Kubernetes, or AI processing into the MVP.
- Obtain explicit approval before changing architectural boundaries or MVP scope.

## Repository and Code Organization

- This repository is a monorepo. Backend implementation belongs under `backend/`.
- Do not inspect or modify `frontend/` unless the task explicitly includes frontend work.
- Follow the backend package/module structure defined in `docs/modules/conventions.md`.
- Keep external HTTP/SSE adapters under the top-level `api/` package, separate from module internals.
- Each business module exposes only a small public contract under `modules/<module>/api/`.
- Keep module implementation details under `modules/<module>/internal/`.
- Cross-module synchronous access must go through the target module's public API/interface.
- Never import another module's `internal` classes, repositories, persistence entities, caches, or infrastructure implementations.
- Do not create empty architectural layers or packages merely for structural symmetry.

## Sources

- `docs/architecture/architecture.md`
- `docs/architecture/data-flow.md`
- `docs/architecture/rabbitmq-topology.md`
- `docs/architecture/failure-handling.md`
- `docs/modules/dependency-matrix.md`
- `docs/modules/conventions.md`
