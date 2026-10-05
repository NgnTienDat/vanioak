# Security Guardrails

- Never log plaintext API keys, passwords, access tokens, or other secrets.
- Never persist plaintext ingestion API keys; follow documented hashing and lifecycle rules.
- An API key is scoped to one environment; derive its application from that environment.
- Treat `host_ip` as metadata, never as identity or authorization.
- Enforce authorization server-side for REST and SSE.
- Engineers may access only assigned applications, including their environments, under documented rules.
- Fail closed when credential validity cannot be established.
- Do not bypass Redis-backed alert deduplication when Redis is unavailable.
- Supply secrets through configuration, not source code.
- Do not weaken CORS, authentication, or authorization to simplify tests or development.

## Sources

- `docs/quality/security.md`
- `docs/modules/01-identity.md`
- `docs/modules/02-ingestion.md`
- `docs/architecture/failure-handling.md`
