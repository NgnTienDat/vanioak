package com.h.vanioak.modules.realtime.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid realtime request"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
	NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "Realtime is not ready"),
	OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "Realtime capacity is full");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() { return status; }
	public String getMessage() { return message; }
}
