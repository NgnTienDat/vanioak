# Verification Guardrails

- Follow the testing strategy and relevant acceptance criteria for the behavior changed by the task.
- Run focused tests first; broaden checks according to the affected behavior and dependencies.
- Cover validation and authorization failures where relevant.
- For asynchronous/event changes, verify retry, idempotency, and error accounting.
- For ingestion/processing changes, cover RabbitMQ publisher/consumer failures where relevant.
- For alert changes, verify deduplication and Incident behavior.
- For realtime changes, verify authorization, filters, and reconnect behavior where applicable.
- Use existing test tooling; ordinary feature/fix tasks require approval before introducing a new framework. An explicitly approved bootstrap/setup task may establish the project's initial test tooling.
- Do not weaken, delete, or skip tests merely to get a green build.
- Report which checks ran; never claim unrun tests passed.
- Report environment blockers and pre-existing failures separately from task regressions.

## Sources

- `docs/quality/testing.md`
- `docs/quality/acceptance-criteria.md`
