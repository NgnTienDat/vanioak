package com.h.vanioak.api.identity.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateApiKeyRequest(@JsonProperty("expires_at") Instant expiresAt) { }

