package com.h.vanioak.api.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchPage;
import com.h.vanioak.modules.analysis.api.AnalysisFacade;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;

class AnalysisControllerTest {
	final AnalysisApiService api = mock(AnalysisApiService.class);
	final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
			.standaloneSetup(new AnalysisController(api)).setControllerAdvice(new AnalysisExceptionHandler()).build();

	@Test
	void emptyResultEnvelopeAndDefaultLimit() throws Exception {
		when(api.search(any(), any(), any(), any())).thenReturn(new SearchPage(List.of(), null));
		mvc.perform(get("/api/v1/logs")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.next_cursor").doesNotExist());
		verify(api).search(isNull(), isNull(), isNull(), argThat(f -> f.limit() == 50));
	}

	@Test
	void malformedUuidAndTimeReturn400WithoutInvocation() throws Exception {
		mvc.perform(get("/api/v1/logs?application_id=invalid")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/logs?from=invalid")).andExpect(status().isBadRequest());
		verifyNoInteractions(api);
	}

	@Test
	void identityDenialsAndDependencyErrorsHaveCorrectEnvelopes() throws Exception {
		when(api.search(any(), any(), any(), any())).thenThrow(new com.h.vanioak.modules.identity.api.IdentityException(
				com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED));
		mvc.perform(get("/api/v1/logs")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false));
		doThrow(new AnalysisException(ErrorCode.UNAVAILABLE)).when(api).search(any(), any(), any(), any());
		mvc.perform(get("/api/v1/logs")).andExpect(status().isInternalServerError());
	}

	@Test
	void healthRequiresRangeAndReturnsHourlyEmptyEnvelope() throws Exception {
		mvc.perform(get("/api/v1/analytics/health")).andExpect(status().isBadRequest());
		verifyNoInteractions(api);
		when(api.health(any(), any(), any(), any()))
				.thenReturn(new com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthResult("hour", List.of()));
		mvc.perform(get("/api/v1/analytics/health?from=2026-10-26T00:00:00Z&to=2026-10-26T01:00:00Z"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.bucket").value("hour"))
				.andExpect(jsonPath("$.data.items").isEmpty());
	}
}
