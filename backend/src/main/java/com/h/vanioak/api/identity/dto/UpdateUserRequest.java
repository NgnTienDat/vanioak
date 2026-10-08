package com.h.vanioak.api.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserRequest {

	@JsonSetter(nulls = Nulls.FAIL)
	@Size(max = 100)
	@Pattern(regexp = "(?s).*\\S.*")
	private String username;

	@JsonSetter(nulls = Nulls.FAIL)
	@Size(min = 1)
	private String password;

	@JsonSetter(nulls = Nulls.FAIL)
	private Status status;

	@AssertTrue(message = "At least one supported field is required")
	public boolean isChanged() {
		return username != null || password != null || status != null;
	}

	@JsonAnySetter
	public void rejectUnknown(String field, Object value) {
		throw new IdentityException(ErrorCode.INVALID_REQUEST);
	}
}
