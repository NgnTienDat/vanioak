# Security Specification

## 1. Authentication

### Human users
- `ADMIN` and `ENGINEER` authenticate through the Backend API.
- Protected REST endpoints require a valid authenticated user.
- Passwords must be stored using a strong one-way password hash.
- Authentication secrets/tokens must never be logged.

### Log sources
Applications authenticate to the Ingestion API using an API key.

Each API key belongs to exactly one environment, and therefore indirectly to exactly one application:

```mermaid
flowchart LR
    A["Application"] --> E["Environment"]
    E --> K["API Key"]
```

The API key is not derived from `host_ip`. `host_ip` is log/source metadata, not the primary authentication mechanism.

## 2. Authorization

### Admin
Admin may:
- manage applications and environments;
- manage engineers and application assignments;
- create/revoke/rotate API keys;
- manage alert rules;
- view logs/incidents/analytics for all applications.

### Engineer
Engineer may:
- view logs, realtime streams, incidents and analytics only for assigned applications;
- access all environments belonging to an assigned application.

Authorization must be enforced on the server for REST and SSE. Never rely on frontend filtering.

## 3. API Key Validation

```mermaid
flowchart TD
    I["Ingestion"] --> L{"Local RAM cache hit?"}
    L -->|"Yes"| V["Validate status + app/environment"]
    L -->|"No"| ID["Identity Module"]
    ID --> R{"Redis hit?"}
    R -->|"Yes"| V
    R -->|"No"| PG[("PostgreSQL")]
    PG --> V
```

Rules:
- Store only a secure hash/fingerprint of the secret in PostgreSQL.
- Redis/local cache must not become the source of truth.
- Revocation/rotation must invalidate cached credentials or ensure they expire within the configured cache TTL.
- Expired/revoked credentials are rejected.
- If validity cannot be safely determined, fail closed.

## 4. API Key Lifecycle

Supported operations:
- create;
- revoke;
- rotate.

The plaintext API key is returned only when it is created/rotated and must not be recoverable later from storage.

## 5. Sensitive Data

Never log:
- passwords;
- JWT/session secrets;
- plaintext API keys;
- Telegram bot token; 
- database credentials.

Secrets are supplied through environment variables or Docker secrets/configuration, never committed to source control.

## 6. SSE Security

Every SSE connection must be authenticated.

Before sending an event, Realtime must enforce application authorization. Client-provided application/environment filters may narrow access but must never expand it.

## 7. Minimum Security Rules

- Validate all request payloads.
- Use parameterized database access.
- Restrict CORS to configured frontend origins.
- Apply reasonable ingestion payload/batch-size limits.
- Do not expose stack traces or secrets in API responses.
- Use the common API error envelope.
