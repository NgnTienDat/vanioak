package com.h.vanioak.modules.alert.api;

import org.springframework.http.HttpStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid cursor"),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
	RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Alert rule not found"),
	INCIDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Incident not found"),
	RULE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Alert rule already exists"),
	UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

	private final HttpStatus status;
	private final String message;
}
