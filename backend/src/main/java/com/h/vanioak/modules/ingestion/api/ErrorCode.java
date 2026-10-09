package com.h.vanioak.modules.ingestion.api;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "Invalid API key"),
	DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");

	private final HttpStatus status;
	private final String message;
}
