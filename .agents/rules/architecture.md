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
- Organize business code by module first, then by capability/feature inside the owning module.
- Do not create global `controller`, `service`, `repository`, or `entity` packages.

Use the following structure where the corresponding responsibilities exist:

```text
com.h.vanioak/
├── api/
│   └── <module>/
│       ├── <Resource>Controller.java
│       ├── <Module>ExceptionHandler.java
│       └── dto/
│           ├── <Action>Request.java
│           └── <Resource>Response.java
│
├── modules/
│   └── <module>/
│       ├── api/
│       │   ├── <Capability>Facade.java
│       │   └── <Module>Exception.java
│       │
│       └── internal/
│           └── <feature>/
│               ├── <Resource>Service.java
│               ├── <Resource>Entity.java
│               └── <Resource>Repository.java
│
└── common/
    └── <technical-concern>/
```

- Keep external HTTP/SSE adapters under top-level `api/<module>/`.
- Top-level `api/` handles transport concerns; `modules/<module>/api/` defines public Java contracts.
- Each business module exposes only a small public contract under `modules/<module>/api/`.
- Public contracts may expose interfaces, commands, DTOs, value types, exceptions, and published events when required.
- Never expose persistence entities, repositories, caches, or infrastructure types through module public contracts.
- Keep module implementation details under `modules/<module>/internal/`.
- Group related services, entities, repositories, and supporting types under the same internal feature package.
- Place module-specific consumers, publishers, configuration, caches, clients, and storage adapters inside the owning module.
- Published module events may live under `modules/<module>/api/events/` when they are part of the documented public module contract.
- Cross-module synchronous access must go through the target module's public API/interface.
- Never import another module's `internal` classes, repositories, persistence entities, caches, or infrastructure implementations.
- Controllers delegate use cases to the owning module's public contract.
- Avoid controller-level orchestration across multiple modules; cross-module workflow coordination belongs inside an owning module/service boundary.
- Keep `common/` limited to reusable technical primitives. It must not depend on business modules or contain business rules.
- Create only packages and classes required by implemented behavior. The illustrated structure does not require every module to contain every component.

## Sources

- `docs/architecture/architecture.md`
- `docs/architecture/data-flow.md`
- `docs/architecture/rabbitmq-topology.md`
- `docs/architecture/failure-handling.md`
- `docs/modules/dependency-matrix.md`
- `docs/modules/conventions.md`
