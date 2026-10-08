package com.h.vanioak.api.identity.dto;

import java.util.UUID;

import com.h.vanioak.modules.identity.api.UserFacade.Status;

public record UserResponse(UUID id, String username, String role, Status status) {
}
