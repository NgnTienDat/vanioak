# Identity Module

## Purpose
Manage human users, applications, environments, application-scoped access, and application ingestion credentials.

## Input
### REST/API operations
- User login credentials.
- Admin commands for application/environment management.
- Admin commands for assigning/removing Engineer access.
- Admin commands for creating/revoking/rotating ingestion API keys.
### Internal calls
- Ingestion Module asks whether an API key is valid and which application/environment it belongs to.
- Backend API asks for authorization information.

## Responsibility
- Authenticate human users.
- Issue/validate user authentication credentials/tokens.
- Manage `users`.
- Manage `applications` and `environments`.
- Manage `user_application_access`.
- Generate, revoke and rotate application/environment-scoped API keys.
- Store credential metadata in PostgreSQL; never store plaintext API keys.
- Maintain API-key lookup cache in Redis.
- Expose an internal service contract for fast API-key verification.
- Provide application-level authorization information for Engineer users.

## Output
- Authenticated user identity and role.
- Application/environment metadata.
- Application access decisions.
- API-key verification result:
  - valid/invalid
  - application_id
  - environment_id
  - credential status/expiry
- Admin management responses through Backend API.

## Dependencies
- PostgreSQL
- Redis
- Password hashing/authentication library
- Backend API layer

## Error handling
- Invalid credentials -> authentication failure.
- Revoked/expired API key -> reject.
- Unknown application/environment -> reject.
- PostgreSQL unavailable -> fail closed for credential-management operations; do not invent authorization.
- Redis unavailable during API-key lookup -> fall back to PostgreSQL only where safe; otherwise reject and expose dependency failure.
- Cache contains stale revoked key -> credential revocation path must invalidate/update Redis.

## Must NOT do
- Must not ingest or persist application logs.
- Must not consume `raw.queue`.
- Must not create incidents.
- Must not send Telegram messages.
- Must not stream logs to clients.
- Must not decide alert thresholds.
- Must not store plaintext passwords or API keys.
