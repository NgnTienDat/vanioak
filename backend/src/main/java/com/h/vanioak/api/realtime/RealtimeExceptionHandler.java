package com.h.vanioak.api.realtime;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.realtime.api.RealtimeException;

@RestControllerAdvice(assignableTypes = RealtimeController.class)
@Order(0)
public class RealtimeExceptionHandler {
	@ExceptionHandler(RealtimeException.class)
	public ResponseEntity<ApiResponse<Object>> realtime(RealtimeException ex, HttpServletResponse response) {
		if (response.isCommitted()) return null;
		return ResponseEntity.status(ex.getErrorCode().getStatus()).contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<ApiResponse<Object>> identity(IdentityException ex, HttpServletResponse response) {
		if (response.isCommitted()) return null;
		return ResponseEntity.status(ex.getErrorCode().getStatus()).contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	public ResponseEntity<ApiResponse<Object>> invalid(Exception ex, HttpServletResponse response) {
		if (response.isCommitted()) return null;
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("Invalid realtime request"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Object>> unavailable(Exception ex, HttpServletResponse response) {
		if (response.isCommitted()) return null;
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
				.body(ApiResponse.error("An unexpected error occurred"));
	}
}
