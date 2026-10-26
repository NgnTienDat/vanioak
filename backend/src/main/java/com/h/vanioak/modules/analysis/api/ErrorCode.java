package com.h.vanioak.modules.analysis.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_QUERY(HttpStatus.BAD_REQUEST, "Invalid analysis query"),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid analysis cursor"),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
	UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis query unavailable");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}
}
