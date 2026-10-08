package com.h.vanioak.api.identity.security;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

import tools.jackson.databind.ObjectMapper;

public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger LOG = LoggerFactory.getLogger(SecurityErrorHandler.class);
	private final ObjectMapper mapper;

	public SecurityErrorHandler(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		write(response, ErrorCode.INVALID_CREDENTIALS.getStatus(), ErrorCode.INVALID_CREDENTIALS.getMessage());
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		write(response, ErrorCode.ACCESS_DENIED.getStatus(), ErrorCode.ACCESS_DENIED.getMessage());
	}

	public void authenticationFailure(HttpServletResponse response, RuntimeException exception) throws IOException {
		if (exception instanceof IdentityException identity) {
			write(response, identity.getErrorCode().getStatus(), identity.getErrorCode().getMessage());
		} else {
			LOG.error("Unexpected authentication failure, type: {}, stack trace: {}",
					exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
			write(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
		}
	}

	private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
	}
}
