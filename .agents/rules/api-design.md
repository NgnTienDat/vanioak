# API Implementation Guardrails

- Follow `docs/contracts/openapi.yaml` as the exact REST contract.
- Do not invent endpoints, fields, status codes, or request/response formats.
- Use the documented common REST response envelope, including for errors.
- Use documented Bearer authentication for protected human-user APIs and API-key authentication for ingestion.
- Enforce authorization server-side; client filters must not expand application/environment access.
- Keep controllers/adapters thin; they must not own business rules or persistence logic.
- Do not expose persistence entities or internal module types through transport APIs.
- Follow the documented cursor contract for search pagination.
- Implement realtime through SSE, following its authentication, filters, events, and reconnect behavior.
- Obtain approval and update the normative contract before implementing a required contract change.

## Sources

- `docs/contracts/openapi.yaml`
- `docs/contracts/rest-api.md`
- `docs/contracts/sse-api.md`
- `docs/quality/security.md`
- `docs/modules/conventions.md`