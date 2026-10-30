package com.h.vanioak.api.ingestion.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

import com.h.vanioak.api.ingestion.IngestionExceptionHandler;
import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;

class IngestionConsoleLoggingTest {

	@AfterEach
	void clearSecurityContext() { SecurityContextHolder.clearContext(); }

	@Test
	void authenticationRejectionsLogAtWarnWithoutRequestSecrets() throws Exception {
		var ingestion = mock(IngestionFacade.class);
		var chain = mock(FilterChain.class);
		var filter = new ApiKeyAuthenticationFilter(ingestion, new ObjectMapper());
		try (var logs = new Capture(ApiKeyAuthenticationFilter.class)) {
			for (var code : ErrorCode.values()) {
				doThrow(new IngestionException(code)).when(ingestion).authenticate(anyString());
				var request = request();
				var response = new MockHttpServletResponse();
				filter.doFilter(request, response, chain);
				assertEquals(code.getStatus().value(), response.getStatus());
				assertTrue(response.getContentAsString().contains(code.getMessage()));
			}
			assertEquals(2, logs.appender.list.size());
			for (var event : logs.appender.list) {
				assertEquals(Level.WARN, event.getLevel());
				assertTrue(event.getFormattedMessage().startsWith("Ingestion request rejected: status="));
				assertFalse(event.getFormattedMessage().contains("SENSITIVE"));
				assertEquals(null, event.getThrowableProxy());
			}
		}
		verifyNoInteractions(chain);
	}

	@Test
	void securityEntryPointsLogSafe401And403Reasons() throws Exception {
		var filter = new ApiKeyAuthenticationFilter(mock(IngestionFacade.class), new ObjectMapper());
		try (var logs = new Capture(ApiKeyAuthenticationFilter.class)) {
			var unauthorized = new MockHttpServletResponse();
			filter.commence(request(), unauthorized, new BadCredentialsException("SENSITIVE-exception"));
			var forbidden = new MockHttpServletResponse();
			filter.handle(request(), forbidden, new AccessDeniedException("SENSITIVE-exception"));
			assertEquals(401, unauthorized.getStatus());
			assertEquals(403, forbidden.getStatus());
			assertEquals(List.of("Ingestion request rejected: status=401, reason=Invalid API key",
					"Ingestion request rejected: status=403, reason=Access denied"),
					logs.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
		}
	}

	@Test
	void controllerBusinessFailuresLogStatusAndCodeWithUnchangedResponse() {
		try (var logs = new Capture(IngestionExceptionHandler.class)) {
			for (var code : ErrorCode.values()) {
				var response = new IngestionExceptionHandler().handleIngestion(new IngestionException(code));
				assertEquals(code.getStatus(), response.getStatusCode());
				assertEquals("Ingestion request rejected: status=" + code.getStatus().value() + ", code=" + code.name(),
						logs.appender.list.getLast().getFormattedMessage());
				assertEquals(Level.WARN, logs.appender.list.getLast().getLevel());
				assertEquals(null, logs.appender.list.getLast().getThrowableProxy());
			}
		}
	}

	@Test
	void unexpectedFailureKeepsSingleSafeErrorWithoutDuplicateWarn() throws Exception {
		var ingestion = mock(IngestionFacade.class);
		when(ingestion.authenticate(anyString())).thenThrow(new IllegalStateException("SENSITIVE-exception"));
		var chain = mock(FilterChain.class);
		try (var logs = new Capture(ApiKeyAuthenticationFilter.class)) {
			var response = new MockHttpServletResponse();
			new ApiKeyAuthenticationFilter(ingestion, new ObjectMapper()).doFilter(request(), response, chain);
			assertEquals(500, response.getStatus());
			assertEquals(1, logs.appender.list.size());
			assertEquals(Level.ERROR, logs.appender.list.getFirst().getLevel());
			assertEquals("Ingestion authentication failure: IllegalStateException", logs.appender.list.getFirst().getFormattedMessage());
			assertFalse(response.getContentAsString().contains("SENSITIVE"));
		}
		verifyNoInteractions(chain);
	}

	private MockHttpServletRequest request() {
		var request = new MockHttpServletRequest("POST", "/api/v1/logs");
		request.addHeader("X-API-Key", "SENSITIVE-api-key");
		request.addHeader("Authorization", "Bearer SENSITIVE-jwt");
		request.setContent("SENSITIVE-request-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return request;
	}

	private static class Capture implements AutoCloseable {
		final Logger logger;
		final Level previousLevel;
		final boolean previousAdditive;
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Capture(Class<?> type) {
			logger = (Logger) LoggerFactory.getLogger(type);
			previousLevel = logger.getLevel(); previousAdditive = logger.isAdditive();
			appender.start(); logger.addAppender(appender); logger.setLevel(Level.INFO); logger.setAdditive(false);
		}
		@Override public void close() {
			logger.detachAppender(appender); appender.stop();
			logger.setLevel(previousLevel); logger.setAdditive(previousAdditive);
		}
	}
}
