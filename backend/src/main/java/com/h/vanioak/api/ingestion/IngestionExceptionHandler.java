package com.h.vanioak.api.ingestion;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(assignableTypes = IngestionController.class)
@Order(0)
@Slf4j
public class IngestionExceptionHandler {
	@ExceptionHandler(IngestionException.class)
	public ResponseEntity<ApiResponse<Object>> handleIngestion(IngestionException ex) {
		log.warn("Ingestion request rejected: status={}, code={}",
				ex.getErrorCode().getStatus().value(), ex.getErrorCode().name());
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}
}
