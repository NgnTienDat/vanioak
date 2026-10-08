package com.h.vanioak.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

public record CreateUserRequest(@NotBlank @Size(max = 100) String username, @NotEmpty String password) {

	@JsonAnySetter
	public void rejectUnknown(String field, Object value) {
		throw new IdentityException(ErrorCode.INVALID_REQUEST);
	}
}
