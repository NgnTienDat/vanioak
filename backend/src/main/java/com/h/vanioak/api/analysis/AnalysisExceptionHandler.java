package com.h.vanioak.api.analysis;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.identity.api.IdentityException;

@RestControllerAdvice(assignableTypes = AnalysisController.class)
@Order(0)
public class AnalysisExceptionHandler {
	@ExceptionHandler(AnalysisException.class)
	public ResponseEntity<ApiResponse<Object>> analysis(AnalysisException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(ApiResponse.error(ex.getMessage()));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<ApiResponse<Object>> identity(IdentityException ex) {
		return ResponseEntity.status(ex.getErrorCode().getStatus())
				.body(ApiResponse.error(ex.getErrorCode().getMessage()));
	}

	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	public ResponseEntity<ApiResponse<Object>> invalid(Exception ex) {
		return ResponseEntity.badRequest().body(ApiResponse.error("Invalid analysis query"));
	}
}
