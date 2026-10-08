# Implementation Discipline

- Follow existing repository conventions when code establishes them.
- Prefer simple, explicit code; avoid speculative abstractions or empty layers/classes for symmetry.
- Keep controllers/adapters thin and business logic in the owning module.
- Use clear names matching project terminology.
- Use English throughout all source code, including identifiers, comments, documentation comments, and text literals.
- Avoid global mutable state.
- Read environment-specific values and secrets from configuration; do not hard-code them.
- Handle failures explicitly; do not silently swallow exceptions or lose accepted work.
- Preserve stable `event_id` through retries and redelivery.
- Comment only where intent is not obvious from the code.
- Keep changes scoped; avoid unrelated refactors.

## Java Conventions

- Target Java 21 and follow the existing base package `com.h.vanioak`.
- Use `PascalCase` for types, `camelCase` for methods/variables, and `UPPER_SNAKE_CASE` for constants.
- Name types by domain concept and responsibility, such as `AlertController`, `AlertFacade`, `AlertFacadeImpl`, `AlertService`, and `AlertRepository`.
- Prefer constructor injection for required dependencies; declare injected dependencies `private final` where possible.
- Use Lombok only when it is already part of the project's conventions.
- Validate external input using Bean Validation.
- Use request/response DTOs at HTTP boundaries; never expose persistence entities.
- Keep module implementation details private to the owning module.
- Use MapStruct for routine DTO/entity mapping according to the mapping rules below; prefer explicit business code for behavior and state transitions.
- Prefer module-specific exceptions over a shared business-exception hierarchy.
- Follow the persistence strategy defined in `.agents/rules/database.md`.

## Java File Organization

- Keep one top-level type per file and match the filename to that type.
- Organize files as: package declaration, imports, type annotations, type declaration.
- Use explicit imports and the repository's established import grouping and formatting.
- Within a class, normally place constants first, then fields, constructors, public methods, and private helpers.
- Keep related methods together; preserve framework-specific lifecycle ordering where it improves readability.
- Place annotations directly above the type, field, or method they describe.
- Break long parameter lists, constructor calls, builder chains, and stream chains across lines using the repository's formatter.
- Avoid formatting unrelated code as part of a scoped change.

## Responsibilities by Type

- Controllers handle routing, input validation, authenticated request context, DTO conversion, and HTTP/SSE responses.
- Controllers must not access repositories or implement business rules.
- Facade interfaces declare public module use cases using stable Java contracts.
- Facade implementations coordinate internal services and convert internal results into public contract types.
- Services implement use cases, business workflows, and explicit failure handling.
- Define transaction boundaries around the owning use case. Follow the module's established approach rather than adding transactions to every layer.
- Entities represent persistent state and may encapsulate meaningful invariants or state transitions where appropriate.
- Prefer intention-revealing entity methods, such as `resolve()` or `changeStatus()`, over unrestricted setters when state changes require validation.
- Repositories contain persistence access and queries; they do not coordinate business workflows.
- Request DTOs describe external input and its validation constraints.
- Response DTOs describe external output without exposing persistence or provider models.

- Each module defines its expected business exception under `modules/<module>/api/`, for example `IdentityException`.
- Module exceptions extend `RuntimeException` and expose a module-specific `ErrorCode`.
- Each module-specific `ErrorCode` directly defines the Spring `HttpStatus` and client-safe message; it is the single source of business error metadata.
- Services throw module exceptions with the corresponding `ErrorCode`, without creating client-facing messages in services; controllers do not catch them manually.
- Module-specific REST exception handlers live under top-level `api/<module>/` and use `@RestControllerAdvice`.
- Module exception handlers take status and message directly from `ErrorCode` and return the shared `ApiResponse<T>` envelope with `success: false` and `data: null`.
- The global exception handler is reserved for common framework errors such as validation failures and unexpected exceptions.
- Unexpected `500` responses must use a safe generic message and must not expose internal exception messages, stack traces, or secrets.
- Field validation returns `400`, message `Validation failed`, and a field-to-message map in `data` (empty when there are no field errors), without rejected values.
- Log unexpected exception types and stack traces server-side without request bodies, exception messages that may contain secrets, credentials, tokens, or passwords.
- Do not introduce a shared business-exception hierarchy unless multiple modules demonstrate a real need for it.

- Events describe facts using stable identity and contracts suitable for the documented delivery semantics.

## Contracts and Mapping

- Prefer records for immutable commands, DTOs, and event payloads when compatible with serialization and framework requirements.
- Prefer separate files for public contract types.
- Nest very small contract types only when they are truly local to one contract and improve readability.
- Extract contract types into separate files when they are shared or make the facade difficult to read.

### Mapping

- Use MapStruct by default for straightforward object-to-object mapping where fields can be mapped deterministically without business logic.
- Prefer MapStruct over repetitive manual field-by-field mapping between:
  - persistence entities and module public DTOs;
  - module public DTOs and HTTP response DTOs;
  - HTTP request DTOs and module commands where no business decision is involved.
- Keep mappers inside the boundary that owns the mapping:
  - entity/internal model ↔ public module contract: inside the owning module;
  - HTTP request/response ↔ module contract: inside top-level `api/<module>/`.
- Configure MapStruct mappers as Spring components and fail compilation on unmapped target properties unless an omission is intentional.
- Use explicit `@Mapping` declarations when source and target fields differ in name or representation.
- Do not put business rules, authorization, repository access, external calls, or state changes inside MapStruct mappers.
- Do not inject repositories or business services into mappers.
- Do not use MapStruct to bypass entity invariants or state-transition methods. Updates such as `resolve()` or `changeStatus()` belong in the owning service/entity logic.
- Use manual mapping when conversion depends on runtime context, business decisions, security rules, external data, or behavior that would make a generated mapper harder to understand.
- Mapping code must not query storage, invoke external services, or change business state.
- Do not introduce generic mapping abstractions on top of MapStruct.

## Sources

- `docs/modules/conventions.md` and the owning module specification.
- `.agents/rules/database.md`
- `docs/architecture/failure-handling.md`
- `docs/operations/configuration.md`
