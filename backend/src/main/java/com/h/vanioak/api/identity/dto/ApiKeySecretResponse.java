package com.h.vanioak.api.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiKeySecretResponse(ApiKeyResponse credential, @JsonProperty("api_key") String apiKey) {
	@Override
	public String toString() {
		return "ApiKeySecretResponse[credential=" + credential + ", apiKey=[REDACTED]]";
	}
}

