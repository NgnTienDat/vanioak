package com.h.vanioak.modules.processing.api;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	INVALID_RAW_BATCH(HttpStatus.BAD_REQUEST, "Invalid raw log batch"),
	PERSISTENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Log persistence unavailable"),
	PUBLICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Log publication unavailable");

	private final HttpStatus status;
	private final String message;
}
