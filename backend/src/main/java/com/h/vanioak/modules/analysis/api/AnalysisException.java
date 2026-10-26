package com.h.vanioak.modules.analysis.api;

public class AnalysisException extends RuntimeException {
	private final ErrorCode errorCode;

	public AnalysisException(ErrorCode code) {
		super(code.getMessage());
		this.errorCode = code;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
