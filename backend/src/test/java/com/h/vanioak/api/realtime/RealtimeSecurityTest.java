package com.h.vanioak.api.realtime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.h.vanioak.api.analysis.AnalysisApiService;
import com.h.vanioak.api.analysis.AnalysisController;
import com.h.vanioak.api.analysis.AnalysisSecurityConfig;
import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.api.ingestion.IngestionController;
import com.h.vanioak.api.ingestion.security.IngestionSecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;
import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import com.h.vanioak.modules.realtime.internal.config.RealtimeProperties;

@WebMvcTest({ RealtimeController.class, AnalysisController.class, IngestionController.class })
@Import({ RealtimeSecurityConfig.class, AnalysisSecurityConfig.class, IdentitySecurityConfig.class,
		IngestionSecurityConfig.class, RealtimeExceptionHandler.class, RealtimeSecurityTest.Options.class })
class RealtimeSecurityTest {
	@Autowired MockMvc mvc;
	@MockitoBean AuthFacade auth;
	@MockitoBean RealtimeApiService realtime;
	@MockitoBean AnalysisApiService analysis;
	@MockitoBean IngestionFacade ingestion;

	@Test
	void exactBearerRouteAndPrecommitJsonErrors() throws Exception {
		when(auth.authenticateAccessToken("good"))
				.thenReturn(new AuthFacade.AuthenticatedUser(UUID.randomUUID(), "ENGINEER"));
		when(realtime.open(any(), any(), any(), any(), any(), any()))
				.thenThrow(new RealtimeException(ErrorCode.NOT_READY));
		mvc.perform(get(RealtimeController.PATH).accept("text/event-stream"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get(RealtimeController.PATH).header("X-API-Key", "key").accept("text/event-stream"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(realtime);
		mvc.perform(get(RealtimeController.PATH).header("Authorization", "Bearer good")
					.accept("text/event-stream"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
				.andExpect(jsonPath("$.success").value(false));
		mvc.perform(get(RealtimeController.PATH + "?application_id=bad")
					.header("Authorization", "Bearer good").accept("text/event-stream"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		mvc.perform(get(RealtimeController.PATH + "/other").header("Authorization", "Bearer good"))
				.andExpect(status().isForbidden());
	}

	@Test
	void restrictedCorsAndNoChangeToOtherRouteChains() throws Exception {
		mvc.perform(options(RealtimeController.PATH).header("Origin", "https://trusted.example")
					.header("Access-Control-Request-Method", "GET")
					.header("Access-Control-Request-Headers", "authorization"))
				.andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://trusted.example"));
		mvc.perform(options(RealtimeController.PATH).header("Origin", "https://evil.example")
					.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
		mvc.perform(get("/api/v1/logs")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/logs").contentType("application/json").content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void initializedEmitterUsesExactSseFrames() throws Exception {
		when(auth.authenticateAccessToken("good"))
				.thenReturn(new AuthFacade.AuthenticatedUser(UUID.randomUUID(), "ENGINEER"));
		var subscription = mock(RealtimeFacade.Subscription.class);
		when(realtime.open(any(), any(), any(), any(), any(), any())).thenReturn(subscription);
		var result = mvc.perform(get(RealtimeController.PATH).header("Authorization", "Bearer good")
				.accept("text/event-stream")).andExpect(request().asyncStarted()).andReturn();
		verify(subscription).activate();
		var sink = org.mockito.ArgumentCaptor.forClass(RealtimeFacade.Sink.class);
		verify(realtime).open(any(), any(), any(), any(), any(), sink.capture());
		sink.getValue().send("log", "{\"message\":\"line\\nfield: fake\"}");
		sink.getValue().heartbeat();
		sink.getValue().close();
		mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("event:log\n")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("data:{\"message\":\"line\\nfield: fake\"}\n\n")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(":heartbeat\n\n")));
	}

	@Test
	void dependencyFailureKeepsSanitizedJsonBeforeCommit() throws Exception {
		when(auth.authenticateAccessToken("good"))
				.thenReturn(new AuthFacade.AuthenticatedUser(UUID.randomUUID(), "ENGINEER"));
		when(realtime.open(any(), any(), any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("private dependency detail"));
		mvc.perform(get(RealtimeController.PATH).header("Authorization", "Bearer good")
					.accept("text/event-stream"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Options {
		@Bean RealtimeProperties realtimeProperties() {
			return new RealtimeProperties(false, List.of("https://trusted.example"));
		}
	}
}
