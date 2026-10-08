package com.h.vanioak.api.identity.dto;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefreshTokenRequest(@JsonProperty("refresh_token") @NotNull String refreshToken) {
}
