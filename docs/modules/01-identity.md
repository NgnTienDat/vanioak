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

## Lifecycle and bootstrap

Disabled user/application/environment behavior is owned by [Security](../quality/security.md#disabled-state). Identity returns active-state context with credential/authorization decisions; credential caches must respect its invalidation/expiry rules.

Identity owns the configured-username Admin bootstrap described in [Configuration](../operations/configuration.md#initial-admin-bootstrap). When enabled, an absent username creates an ACTIVE ADMIN; an existing ADMIN is unchanged, and an ENGINEER username fails startup without promotion. Bootstrap is not a user-management REST feature.

## Human authentication lifecycle

This is the target contract; this documentation change does not implement Auth endpoints, token persistence or blacklist checks.

- Login uses username/password for ACTIVE ADMIN/ENGINEER users and issues an access JWT and a refresh JWT. Use separate signing keys and distinguish token type when verifying; a refresh JWT is never an access credential.
- Access JWTs identify the user through a UUID subject and include issued-at, expiration, a unique UUID `jti` and `token_use=access`. Refresh JWTs include a UUID subject, issued-at, expiration, `jti=token_id`, `token_use=refresh` and `family_id`. Verify signature, expiration, required claims and UUIDs before using them; signed refresh metadata must match the stored user, family and expiry.
- Identity owns authoritative refresh metadata in PostgreSQL: `token_id`, `family_id`, `parent_token_id`, `user_id`, `expires_at` (expiry), `used` and `revoked`. Never store raw refresh JWTs. Each login creates a separate family representing one refresh session; other devices/sessions have separate families.
- Refresh verifies the refresh JWT and locks its metadata before checking current user status and eligibility. In one PostgreSQL transaction, mark the old token used and insert one new token in the same family with the old token as parent. Serialize rotation and family revocation so concurrent refresh cannot create multiple successors or leave an active successor after family revocation. Only return the new token pair after commit.
- Reuse of a verified token whose metadata is already used returns `401` and revokes its whole family, including the current descendant. Persist that revocation even though the request fails. Invalid signatures or unmatched metadata must not revoke an unrelated family. Retain used-token metadata while its family has live refresh tokens so reuse can be detected.
- Logout authenticates the current refresh JWT from the JSON body and revokes that refresh session's family, preventing further refresh; other families are unaffected. The caller may also send the current access JWT in `Authorization: Bearer ...`. If it verifies and is unexpired, require its user to match the refresh session, extract its `jti` and blacklist it in Redis until its `exp`. An absent, invalid or expired optional access token does not prevent logout with a valid refresh token and is not blacklisted. A valid access token for a different user returns `401` without revoking either user's credentials.
- Redis access-token blacklist is authoritative revocation state, not an optional cache. Store only the access `jti` in a namespaced key with a non-secret marker; never store raw JWTs. Set expiration to the token's absolute `exp` (remaining lifetime), do not extend it on repeated writes, and do not evict or clear entries before expiry.
- A protected request verifies the access JWT, checks its `jti` against Redis, rejects blacklisted tokens, then loads the current user/role/status from PostgreSQL before establishing authentication. Missing/invalid/expired/blacklisted tokens or missing users return `401`; disabled users or insufficient permissions return `403`. JWT role claims cannot override DB authorization. Redis/DB failures fail closed with a safe `500`.
- Refresh rotation, refresh-family revocation and access revocation are separate. Only explicitly blacklisted access tokens are revoked: logout does not revoke all previously issued access tokens, and refresh rotation/replay alone does not revoke access tokens. No auth cookies, `/me`, or revocation broadcast are added.

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
- PostgreSQL unavailable during refresh metadata or current-user lookup -> fail closed with a safe `500`; do not invent authentication or authorization.
- Redis unavailable during access-blacklist check/write -> fail closed with a safe `500`; never bypass the blacklist or claim successful logout without a required write.
- Redis unavailable during API-key lookup -> fall back to PostgreSQL only where safe; otherwise reject and expose dependency failure. This fallback does not apply to the auth blacklist.
- Cache contains stale revoked key -> credential revocation path must invalidate/update Redis.

## Must NOT do
- Must not ingest or persist application logs.
- Must not consume `raw.queue`.
- Must not create incidents.
- Must not send Telegram messages.
- Must not stream logs to clients.
- Must not decide alert thresholds.
- Must not store plaintext passwords or API keys.
