package com.h.vanioak.api.ingestion;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.ingestion.api.IngestionException;

@RestControllerAdvice(assignableTypes = IngestionController.class)
@Order(0)
public class IngestionExceptionHandler {
	@ExceptionHandler(IngestionException.class)
	public ResponseEntity<ApiResponse<Object>> handleIngestion(IngestionException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}
}
