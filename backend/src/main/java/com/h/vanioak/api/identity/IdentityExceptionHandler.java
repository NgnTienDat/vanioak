package com.h.vanioak.api.identity;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

@RestControllerAdvice(assignableTypes = {UserController.class, AuthController.class, ApplicationController.class, ApiKeyController.class})
@Order(0)
public class IdentityExceptionHandler {

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<ApiResponse<Object>> handleIdentity(IdentityException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus())
				.body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ApiResponse<Object>> handleInvalidRequest(Exception ex) {
		return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
				.body(ApiResponse.error(ErrorCode.INVALID_REQUEST.getMessage()));
	}
}
