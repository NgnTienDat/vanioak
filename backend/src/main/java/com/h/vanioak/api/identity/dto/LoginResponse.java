package com.h.vanioak.api.identity.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("refresh_token") String refreshToken,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("expires_in") long expiresIn,
		UserSummary user) {

	public record UserSummary(UUID id, String username, String role) { }
}
