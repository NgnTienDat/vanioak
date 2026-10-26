package com.h.vanioak.modules.processing.api;

import lombok.Getter;

@Getter
public class ProcessingException extends RuntimeException {
	private final ErrorCode errorCode;

	public ProcessingException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
