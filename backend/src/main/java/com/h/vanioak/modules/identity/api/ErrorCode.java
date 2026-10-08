package com.h.vanioak.modules.identity.api;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
	APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Application not found"),
	ENVIRONMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Environment not found"),
	API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "API key not found"),
	ADMIN_USER_FORBIDDEN(HttpStatus.FORBIDDEN, "Admin users cannot be managed through this API"),
	USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "Username already exists"),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid cursor");

	private final HttpStatus status;
	private final String message;
}
