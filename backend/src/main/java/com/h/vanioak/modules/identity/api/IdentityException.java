package com.h.vanioak.modules.identity.api;

import lombok.Getter;

@Getter
public class IdentityException extends RuntimeException {

	private final ErrorCode errorCode;

	public IdentityException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
