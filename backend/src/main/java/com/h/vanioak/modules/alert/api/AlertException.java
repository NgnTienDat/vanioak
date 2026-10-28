package com.h.vanioak.modules.alert.api;

import lombok.Getter;

@Getter
public class AlertException extends RuntimeException {
	private final ErrorCode errorCode;

	public AlertException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
