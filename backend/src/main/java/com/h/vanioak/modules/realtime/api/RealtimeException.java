package com.h.vanioak.modules.realtime.api;

public class RealtimeException extends RuntimeException {
	private final ErrorCode errorCode;

	public RealtimeException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() { return errorCode; }
}
