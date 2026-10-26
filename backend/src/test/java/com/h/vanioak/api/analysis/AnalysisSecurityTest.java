package com.h.vanioak.api.analysis;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.api.ingestion.security.IngestionSecurityConfig;
import com.h.vanioak.api.ingestion.IngestionController;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchPage;

@WebMvcTest({ AnalysisController.class, IngestionController.class })
@Import({ AnalysisSecurityConfig.class, IdentitySecurityConfig.class, IngestionSecurityConfig.class })
class AnalysisSecurityTest {
	@Autowired
	MockMvc mvc;
	@MockitoBean
	AuthFacade auth;
	@MockitoBean
	AnalysisApiService api;
	@MockitoBean
	IngestionFacade ingestion;
	final UUID actor = UUID.randomUUID();

	@BeforeEach
	void setup() {
		when(auth.authenticateAccessToken("good")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		when(api.search(any(), any(), any(), any())).thenReturn(new SearchPage(List.of(), null));
	}

	@Test
	void bearerGetIsAllowedButApiKeyCannotSubstitute() throws Exception {
		mvc.perform(get("/api/v1/logs").header("Authorization", "Bearer good")).andExpect(status().isOk());
		clearInvocations(api);
		mvc.perform(get("/api/v1/logs").header("X-API-Key", "key")).andExpect(status().isUnauthorized());
		verifyNoInteractions(api);
	}

	@Test
	void adminAllowedUnknownRolesDeniedAndFutureRoutesRemainDenied() throws Exception {
		when(auth.authenticateAccessToken("good")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ADMIN"));
		mvc.perform(get("/api/v1/logs").header("Authorization", "Bearer good")).andExpect(status().isOk());
		when(auth.authenticateAccessToken("good")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "OTHER"));
		mvc.perform(get("/api/v1/logs").header("Authorization", "Bearer good")).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/logs/other").header("Authorization", "Bearer good")).andExpect(status().isForbidden());
	}

	@Test
	void bearerDoesNotBypassIngestionApiKeyChain() throws Exception {
		when(ingestion.authenticate(null)).thenThrow(new com.h.vanioak.modules.ingestion.api.IngestionException(
				com.h.vanioak.modules.ingestion.api.ErrorCode.INVALID_API_KEY));
		mvc.perform(post("/api/v1/logs").header("Authorization", "Bearer good").contentType("application/json")
				.content("{}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/logs/batch").header("Authorization", "Bearer good").contentType("application/json")
				.content("{}")).andExpect(status().isUnauthorized());
		verify(ingestion, times(2)).authenticate(null);
		verify(ingestion, never()).ingest(any(), any(), any());
	}

	@Test
	void disabledOrRevokedHumanAuthenticationNeverReachesAnalysis() throws Exception {
		when(auth.authenticateAccessToken("good")).thenThrow(new com.h.vanioak.modules.identity.api.IdentityException(
				com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED));
		mvc.perform(get("/api/v1/logs").header("Authorization", "Bearer good")).andExpect(status().isForbidden());
		verifyNoInteractions(api);
	}

	@Test
	void healthUsesSameHumanSecurityBoundary() throws Exception {
		String route = "/api/v1/analytics/health?from=2026-10-26T00:00:00Z&to=2026-10-26T01:00:00Z";
		mvc.perform(get(route).header("X-API-Key", "key")).andExpect(status().isUnauthorized());
		verifyNoInteractions(api);
		when(api.health(any(), any(), any(), any()))
				.thenReturn(new com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthResult("hour", List.of()));
		mvc.perform(get(route).header("Authorization", "Bearer good")).andExpect(status().isOk());
	}
}
