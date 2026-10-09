package com.h.vanioak.api.ingestion.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter implements AuthenticationEntryPoint, AccessDeniedHandler {
	private final IngestionFacade ingestion;
	private final ObjectMapper mapper;

	public ApiKeyAuthenticationFilter(IngestionFacade ingestion, ObjectMapper mapper) {
		this.ingestion = ingestion;
		this.mapper = mapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		VerificationResult verified;
		try {
			verified = ingestion.authenticate(request.getHeader("X-API-Key"));
		} catch (IngestionException ex) {
			SecurityContextHolder.clearContext();
			write(response, ex.getErrorCode().getStatus(), ex.getErrorCode().getMessage());
			return;
		} catch (RuntimeException ex) {
			SecurityContextHolder.clearContext();
			log.error("Ingestion authentication failure: {}", ex.getClass().getSimpleName());
			write(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
			return;
		}
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(verified, null, List.of()));
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
		write(response, ErrorCode.INVALID_API_KEY.getStatus(), ErrorCode.INVALID_API_KEY.getMessage());
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
		write(response, HttpStatus.FORBIDDEN, "Access denied");
	}

	private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
	}
}
