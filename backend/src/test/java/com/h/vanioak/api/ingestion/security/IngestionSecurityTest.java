package com.h.vanioak.api.ingestion.security;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.api.ingestion.IngestionController;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.ingestion.internal.IngestionService;
import com.h.vanioak.modules.ingestion.internal.RawLogPublisher;

@WebMvcTest(IngestionController.class)
@Import({IngestionSecurityConfig.class, IdentitySecurityConfig.class, IngestionService.class})
class IngestionSecurityTest {
	private static final String LOG = """
			{"application":"example","environment":"DEV","host_ip":"127.0.0.1",
			"level":"INFO","message":"test log","timestamp":"2026-10-08T10:00:00Z","trace_id":"trace"}
			""";
	@Autowired
	private MockMvc mvc;
	@MockitoBean
	private ApiKeyFacade identity;
	@MockitoBean
	private AuthFacade humanAuth;
	@MockitoSpyBean
	private IngestionService ingestion;
	@MockitoBean
	private RawLogPublisher publisher;
	private VerificationResult context;

	@BeforeEach
	void setup() {
		context = new VerificationResult(true, UUID.randomUUID(), UUID.randomUUID(), "example",
				EnvironmentName.DEV, Instant.now().plusSeconds(60));
		when(identity.verify(anyString())).thenReturn(context);
	}

	@Test
	void bothRoutesUseApiKeyWithoutHumanJwtAndLocalHitAvoidsIdentity() throws Exception {
		String key = "test-key-" + UUID.randomUUID();
		doAnswer(call -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			assertEquals(context, authentication.getPrincipal());
			assertNull(authentication.getCredentials());
			assertFalse(authentication.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")));
			return call.callRealMethod();
		}).when(ingestion).validateBinding(any(), anyList());
		var response = accepted(mvc.perform(post("/api/v1/logs").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(LOG))).andReturn();
		assertNull(response.getRequest().getSession(false));
		accepted(mvc.perform(post("/api/v1/logs/batch").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"logs\":[" + LOG + "," + LOG + "]}")));
		verify(identity, times(1)).verify(key);
		verifyNoInteractions(humanAuth);
		failure(mvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(LOG)), 401, "Invalid API key");
	}

	@Test
	void optionalHumanBearerDoesNotBlockValidApiKeyOrAuthenticateIt() throws Exception {
		for (String authorization : new String[] {"Bearer malformed", "Bearer expired-token", "Basic ignored"}) {
			accepted(mvc.perform(post("/api/v1/logs").header("X-API-Key", "valid-" + UUID.randomUUID())
					.header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON).content(LOG)));
		}
		verifyNoInteractions(humanAuth);
	}

	@Test
	void missingEmptyAndInvalidKeysRejectBeforeBodyValidation() throws Exception {
		failure(mvc.perform(post("/api/v1/logs").header(HttpHeaders.AUTHORIZATION, "Bearer human-token")
				.contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "Invalid API key");
		failure(mvc.perform(post("/api/v1/logs/batch").header("X-API-Key", "")
				.contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "Invalid API key");
		verifyNoInteractions(identity, humanAuth);
		when(identity.verify("revoked-or-expired")).thenReturn(new VerificationResult(false, null, null, null, null, null));
		failure(mvc.perform(post("/api/v1/logs").header("X-API-Key", "revoked-or-expired")
				.contentType(MediaType.APPLICATION_JSON).content(LOG)), 401, "Invalid API key");
	}

	@Test
	void bindingChecksApplicationAndEveryBatchEnvironmentAndAllowsLowercaseAlias() throws Exception {
		failure(request("", LOG.replace("example", "other-application")), 401, "Invalid API key");
		failure(request("", LOG.replace("example", "Example")), 401, "Invalid API key");
		failure(request("/batch", "{\"logs\":[" + LOG + "," + LOG.replace("DEV", "TEST") + "]}"),
				401, "Invalid API key");
		accepted(request("", LOG.replace("DEV", "dev")));
	}

	@Test
	void validAuthStillUsesPhaseOneBodyValidation() throws Exception {
		request("", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Validation failed"));
		request("", "{").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
		request("/batch", "{\"logs\":[]}").andExpect(status().isBadRequest());
	}

	@Test
	void identityFailuresAndUnusableDeadlineFailClosedWithSafeUnavailable() throws Exception {
		when(identity.verify("unavailable")).thenThrow(new DataAccessResourceFailureException("Internal connection detail"));
		String response = failure(mvc.perform(post("/api/v1/logs").header("X-API-Key", "unavailable")
				.contentType(MediaType.APPLICATION_JSON).content(LOG)), 503, "Service unavailable")
				.andReturn().getResponse().getContentAsString();
		assertFalse(response.contains("Internal connection detail"));
		when(identity.verify("stale-result")).thenReturn(new VerificationResult(true, context.applicationId(), context.environmentId(),
				"example", EnvironmentName.DEV, Instant.EPOCH));
		failure(mvc.perform(post("/api/v1/logs").header("X-API-Key", "stale-result")
				.contentType(MediaType.APPLICATION_JSON).content(LOG)), 503, "Service unavailable");
	}

	@Test
	void unexpectedFilterFailureIsGenericAndOtherMethodsRemainClosed() throws Exception {
		doThrow(new IllegalStateException("Private failure detail")).when(ingestion).authenticate("unexpected");
		failure(mvc.perform(post("/api/v1/logs").header("X-API-Key", "unexpected")
				.contentType(MediaType.APPLICATION_JSON).content(LOG)), 500, "An unexpected error occurred");
		failure(mvc.perform(get("/api/v1/logs").header("X-API-Key", "valid-key")), 401, "Invalid credentials");
		failure(mvc.perform(post("/api/v1/logs/unknown").header("X-API-Key", "valid-key")), 401, "Invalid credentials");
	}

	@Test
	void httpAcceptanceWaitsForPublisherAndPublisherRejectionIsSafe503() throws Exception {
		var entered = new java.util.concurrent.CountDownLatch(1);
		var confirmed = new java.util.concurrent.CompletableFuture<Void>();
		doAnswer(call -> {
			entered.countDown();
			confirmed.get(5, java.util.concurrent.TimeUnit.SECONDS);
			return null;
		}).when(publisher).publish(any());
		try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
			var http = executor.submit(() -> request("", LOG));
			try {
				org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
				assertFalse(http.isDone());
			} finally { confirmed.complete(null); }
			accepted(http.get(2, java.util.concurrent.TimeUnit.SECONDS));
		}
		doThrow(new com.h.vanioak.modules.ingestion.api.IngestionException(
				com.h.vanioak.modules.ingestion.api.ErrorCode.DEPENDENCY_UNAVAILABLE)).when(publisher).publish(any());
		failure(request("/batch", "{\"logs\":[" + LOG + "]}"), 503, "Service unavailable");
	}

	@Test
	void errorDispatchDoesNotBecomeAnAuthenticationError() throws Exception {
		mvc.perform(post("/api/v1/logs").with(request -> {
			request.setDispatcherType(DispatcherType.ERROR);
			request.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/logs");
			return request;
		}).header("X-API-Key", "test-key")
				.contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
		verifyNoInteractions(identity, humanAuth);
	}

	private ResultActions request(String suffix, String json) throws Exception {
		return mvc.perform(post("/api/v1/logs" + suffix).header("X-API-Key", "test-key-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions accepted(ResultActions result) throws Exception {
		return result.andExpect(status().isAccepted()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accepted").value(true)).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	private ResultActions failure(ResultActions result, int status, String message) throws Exception {
		return result.andExpect(status().is(status)).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}
}
