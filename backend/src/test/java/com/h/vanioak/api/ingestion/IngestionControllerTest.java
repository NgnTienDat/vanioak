package com.h.vanioak.api.ingestion;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;
import com.h.vanioak.modules.ingestion.api.IngestionFacade.Acceptance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class IngestionControllerTest {
	private static final String VALID = """
			{"application":"example","environment":"DEV","host_ip":"source-metadata",
			"level":"INFO","message":"log-message","timestamp":"2026-10-08T10:00:00Z","trace_id":"trace"}
			""";
	private final JsonMapper mapper = JsonMapper.builder().build();
	private MockMvc mvc;
	private final IngestionFacade ingestion = mock(IngestionFacade.class);
	private final VerificationResult context = new VerificationResult(true, UUID.randomUUID(), UUID.randomUUID(),
			"example", EnvironmentName.DEV, Instant.now().plusSeconds(3600));

	@BeforeEach
	void setup() {
		when(ingestion.ingest(any(), anyList())).thenAnswer(call -> new Acceptance(UUID.randomUUID(), ((List<?>) call.getArgument(1)).size()));
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(context, null, List.of()));
		mvc = MockMvcBuilders.standaloneSetup(new IngestionController(ingestion))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler(), new IngestionExceptionHandler()).build();
	}

	@AfterEach
	void clearContext() { SecurityContextHolder.clearContext(); }

	@Test
	void validSingleAndBatchReturnAcceptedWithContractShape() throws Exception {
		accepted(request("", VALID));
		request("", VALID).andExpect(jsonPath("$.data.*", hasSize(2)))
				.andExpect(jsonPath("$.data.accepted_count").doesNotExist());
		verify(ingestion, org.mockito.Mockito.times(2)).ingest(eq(context), argThat(logs -> logs.size() == 1 && logs.getFirst().application().equals("example") && logs.getFirst().environment().equals("DEV")));
		accepted(request("/batch", "{\"logs\":[" + VALID + "]}"));
		request("/batch", "{\"logs\":[" + VALID + "]}").andExpect(jsonPath("$.data.*", hasSize(3)))
				.andExpect(jsonPath("$.data.accepted_count").value(1));
		accepted(request("/batch", "{\"logs\":[" + String.join(",", Collections.nCopies(1000, VALID)) + "]}"));
	}

	@Test
	void publisherUnavailableAndUnexpectedErrorsUseExistingSafeEnvelopes() throws Exception {
		org.mockito.Mockito.doThrow(new com.h.vanioak.modules.ingestion.api.IngestionException(
				com.h.vanioak.modules.ingestion.api.ErrorCode.DEPENDENCY_UNAVAILABLE)).when(ingestion).ingest(any(), anyList());
		request("", VALID).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value("Service unavailable")).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		org.mockito.Mockito.doThrow(new IllegalStateException("Private publisher detail")).when(ingestion).ingest(any(), anyList());
		request("/batch", "{\"logs\":[" + VALID + "]}").andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"));
	}

	@Test
	void preservesContractAllowedValuesAndMetadata() throws Exception {
		for (String environment : List.of("DEV", "TEST", "STAGING", "dev", "test", "staging")) {
			var body = body();
			body.put("environment", environment);
			body.put("application", "");
			body.put("host_ip", "");
			body.put("message", "");
			body.put("trace_id", "");
			body.put("timestamp", "2026-10-08T17:00:00+07:00");
			body.set("metadata", mapper.readTree("{\"nested\":[null,true,3,{\"key\":\"value\"}]}"));
			body.put("unknown_property", "allowed");
			accepted(request("", body.toString()));
		}
		for (String level : List.of("INFO", "WARN", "ERROR", "CRITICAL")) {
			var body = body();
			body.put("level", level);
			accepted(request("", body.toString()));
		}
	}

	@Test
	void requiredFieldsMustBePresentAndNonNull() throws Exception {
		for (String field : List.of("application", "environment", "host_ip", "level", "message", "timestamp", "trace_id")) {
			var missing = body();
			missing.remove(field);
			request("", missing.toString()).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value("Validation failed"));
			var explicitNull = body();
			explicitNull.putNull(field);
			request("", explicitNull.toString()).andExpect(status().isBadRequest());
		}
	}

	@Test
	void rejectsIncorrectJsonPropertyTypesRatherThanCoercingThem() throws Exception {
		for (String field : List.of("application", "environment", "host_ip", "level", "message", "timestamp", "trace_id")) {
			for (String value : List.of("12", "true", "[]", "{}")) {
				var body = body();
				body.set(field, mapper.readTree(value));
				request("", body.toString()).andExpect(status().isBadRequest());
			}
		}
		for (String value : List.of("null", "12", "true", "[]", "\"text\"")) {
			var body = body();
			body.set("metadata", mapper.readTree(value));
			request("", body.toString()).andExpect(status().isBadRequest());
		}
	}

	@Test
	void rejectsInvalidEnumsAndDateTimesWithoutTimezone() throws Exception {
		for (String value : List.of("Debug", "DEBUG", "info", "")) {
			var body = body();
			body.put("level", value);
			request("", body.toString()).andExpect(status().isBadRequest());
		}
		for (String value : List.of("prod", "Dev", "")) {
			var body = body();
			body.put("environment", value);
			request("", body.toString()).andExpect(status().isBadRequest());
		}
		for (String value : List.of("bad-date", "2026-10-08", "2026-10-08T10:00:00", "2026-02-30T10:00:00Z")) {
			var body = body();
			body.put("timestamp", value);
			request("", body.toString()).andExpect(status().isBadRequest());
		}
	}

	@Test
	void applicationLengthMatchesOpenApiWithoutAnArbitraryMessageLimit() throws Exception {
		var body = body();
		body.put("application", "a".repeat(100));
		body.put("message", "m".repeat(20_000));
		accepted(request("", body.toString()));
		body.put("application", "a".repeat(101));
		request("", body.toString()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data.application").exists());
	}

	@Test
	void malformedOrMissingBodiesAndHeaderReturnSafeBadRequest() throws Exception {
		for (String json : List.of("{", "[]", "true", "12", "\"string\"", "null", "")) {
			request("", json).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Invalid request"))
					.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		}
		mvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
	}

	@Test
	void batchEnforcesArraySizeAndNestedValidation() throws Exception {
		for (String json : List.of("{}", "[]", "{\"logs\":null}", "{\"logs\":[]}",
				"{\"logs\":{}}", "{\"logs\":12}", "{\"logs\":[null]}", "{\"logs\":[12]}", "{\"logs\":[{}]}"))
			request("/batch", json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		request("/batch", "{\"logs\":[" + String.join(",", Collections.nCopies(1001, VALID)) + "]}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.logs").exists());
		var invalid = body();
		invalid.remove("message");
		String response = request("/batch", "{\"logs\":[" + invalid + "]}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data['logs[0].message']").exists())
				.andReturn().getResponse().getContentAsString();
		assertFalse(response.contains("source-metadata"));
		assertFalse(response.contains("test-only-api-key"));
	}

	private ObjectNode body() { return (ObjectNode) mapper.readTree(VALID); }

	private ResultActions request(String suffix, String body) throws Exception {
		return mvc.perform(post("/api/v1/logs" + suffix).header("X-API-Key", "test-only-api-key")
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void accepted(ResultActions result) throws Exception {
		result.andExpect(status().isAccepted()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data.accepted").value(true)).andExpect(jsonPath("$.data.request_id").isString());
	}
}
