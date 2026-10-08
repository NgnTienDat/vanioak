package com.h.vanioak.api.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotNull @Size(max = 100) String username, @NotNull String password) {
}
