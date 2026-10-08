package com.h.vanioak.api.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiKeyResponse(UUID id, @JsonProperty("environment_id") UUID environmentId,
		@JsonProperty("key_prefix") String keyPrefix, Status status,
		@JsonProperty("expires_at") Instant expiresAt, @JsonProperty("created_at") Instant createdAt,
		@JsonProperty("revoked_at") Instant revokedAt) { }

