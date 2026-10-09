package com.h.vanioak.modules.ingestion.api;

import lombok.Getter;

@Getter
public class IngestionException extends RuntimeException {
	private final ErrorCode errorCode;

	public IngestionException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
