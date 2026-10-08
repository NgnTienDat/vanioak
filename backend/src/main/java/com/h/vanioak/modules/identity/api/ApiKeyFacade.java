package com.h.vanioak.modules.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiKeyFacade {
	VerificationResult verify(String rawApiKey);
	IssuedKey create(UUID applicationId, UUID environmentId, UUID createdBy, Instant expiresAt);
	CredentialPage list(UUID applicationId, UUID environmentId, String cursor, int limit);
	IssuedKey rotate(UUID credentialId, UUID createdBy);
	CredentialView revoke(UUID credentialId);

	enum Status { ACTIVE, REVOKED, EXPIRED }
	record VerificationResult(boolean valid, UUID applicationId, UUID environmentId) { }
	record CredentialView(UUID id, UUID environmentId, String keyPrefix, Status status,
			Instant expiresAt, Instant createdAt, Instant revokedAt) { }
	record CredentialPage(List<CredentialView> items, String nextCursor) { }
	record IssuedKey(CredentialView credential, String apiKey) {
		@Override
		public String toString() {
			return "IssuedKey[credential=" + credential + ", apiKey=[REDACTED]]";
		}
	}
}

