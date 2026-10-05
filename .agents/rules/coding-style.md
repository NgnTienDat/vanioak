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

## Sources

- `docs/modules/conventions.md` and the owning module specification.
- `docs/architecture/failure-handling.md`
- `docs/operations/configuration.md`
