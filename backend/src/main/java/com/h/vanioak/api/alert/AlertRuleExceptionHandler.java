package com.h.vanioak.api.alert;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

@RestControllerAdvice(assignableTypes = AlertRuleController.class)
@Order(0)
public class AlertRuleExceptionHandler {
	@ExceptionHandler(AlertException.class)
	public ResponseEntity<ApiResponse<Object>> alert(AlertException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<ApiResponse<Object>> identity(IdentityException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ApiResponse<Object>> invalid(Exception ex) {
		return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_REQUEST.getMessage()));
	}
}
