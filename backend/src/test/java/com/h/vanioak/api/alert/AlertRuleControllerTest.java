package com.h.vanioak.api.alert;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import com.h.vanioak.modules.alert.api.ErrorCode;

class AlertRuleControllerTest {
	final AlertRuleApiService api = mock(AlertRuleApiService.class);
	final UUID id = UUID.randomUUID(), env = UUID.randomUUID(), actor = UUID.randomUUID();
	final AlertRuleFacade.Rule view = new AlertRuleFacade.Rule(id, env, AlertRuleFacade.Level.ERROR, 10, 60, 300, 300, true, Instant.EPOCH, Instant.EPOCH);
	final UsernamePasswordAuthenticationToken admin = UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
	MockMvc mvc;
	String body() { return "{\"environment_id\":\""+env+"\",\"level\":\"ERROR\",\"threshold\":10,\"window_seconds\":60,\"cooldown_seconds\":300,\"resolve_after_seconds\":300,\"enabled\":true}"; }
	@BeforeEach void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new AlertRuleController(api)).defaultRequest(get("/").principal(admin))
				.setControllerAdvice(new AlertRuleExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@Test void createHasExistingEnvelopeAndSnakeCaseResponse() throws Exception {
		when(api.create(eq(admin), any())).thenReturn(view);
		mvc.perform(post("/api/v1/alert-rules").contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.*", hasSize(10)))
				.andExpect(jsonPath("$.data.environment_id").value(env.toString())).andExpect(jsonPath("$.data.window_seconds").value(60))
				.andExpect(jsonPath("$.data.environmentId").doesNotExist());
		var expected = new AlertRuleFacade.Create(env, AlertRuleFacade.Level.ERROR, 10, 60, 300, 300, true);
		verify(api).create(admin, expected);
	}

	@Test void listSupportsDefaultsEmptyAndOpaqueCursor() throws Exception {
		when(api.list(admin, null, 50)).thenReturn(new AlertRuleFacade.Page(List.of(), null));
		mvc.perform(get("/api/v1/alert-rules")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)))
				.andExpect(jsonPath("$.data.next_cursor").value(org.hamcrest.Matchers.nullValue()));
		when(api.list(admin, "opaque", 1)).thenReturn(new AlertRuleFacade.Page(List.of(view), "next"));
		mvc.perform(get("/api/v1/alert-rules").param("cursor", "opaque").param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.next_cursor").value("next"));
	}

	@Test void patchCannotChangeScopeAndDeleteDisables() throws Exception {
		when(api.update(eq(admin), eq(id), any())).thenReturn(view); when(api.disable(admin, id)).thenReturn(view);
		mvc.perform(patch("/api/v1/alert-rules/"+id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"threshold\":5,\"environment_id\":\""+UUID.randomUUID()+"\",\"level\":\"CRITICAL\"}"))
				.andExpect(status().isOk());
		verify(api).update(admin, id, new AlertRuleFacade.Update(5, null, null, null, null));
		mvc.perform(patch("/api/v1/alert-rules/"+id).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isOk());
		verify(api).update(admin, id, new AlertRuleFacade.Update(null, null, null, null, null));
		mvc.perform(delete("/api/v1/alert-rules/"+id)).andExpect(status().isOk()); verify(api).disable(admin, id);
	}

	@Test void malformedAndMissingInputsHaveNoServiceCalls() throws Exception {
		for (String body : List.of("{}", "null", "[]", "{", body().replace("10", "0"), body().replace("10", "-1"),
				body().replace("10", "1.5"), body().replace("10", "\"10\""), body().replace("10", "2147483648"),
				body().replace("true", "\"true\""), body().replace("true", "1"), body().replace("ERROR", "WARN"),
				body().replace(env.toString(), "bad-uuid"), body().replace("300", "null"))) {
			mvc.perform(post("/api/v1/alert-rules").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		}
		mvc.perform(patch("/api/v1/alert-rules/bad-id").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		for (String key : List.of("threshold", "window_seconds", "cooldown_seconds", "resolve_after_seconds", "enabled")) {
			mvc.perform(patch("/api/v1/alert-rules/"+id).contentType(MediaType.APPLICATION_JSON).content("{\""+key+"\":null}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(patch("/api/v1/alert-rules/"+id).contentType(MediaType.APPLICATION_JSON).content("{\"threshold\":0}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/alert-rules").param("limit", "bad")).andExpect(status().isBadRequest()); verifyNoInteractions(api);
	}

	@Test void integerBoundaryIsPreservedWithoutTruncation() throws Exception {
		when(api.create(eq(admin), any())).thenReturn(view);
		mvc.perform(post("/api/v1/alert-rules").contentType(MediaType.APPLICATION_JSON)
				.content(body().replace("\"threshold\":10", "\"threshold\":2147483647")))
				.andExpect(status().isCreated());
		var captured = ArgumentCaptor.forClass(AlertRuleFacade.Create.class); verify(api).create(eq(admin), captured.capture());
		assertEquals(Integer.MAX_VALUE, captured.getValue().threshold());
	}

	@Test void safeBusinessErrorsKeepContractStatuses() throws Exception {
		when(api.create(eq(admin), any())).thenThrow(new AlertException(ErrorCode.RULE_ALREADY_EXISTS));
		mvc.perform(post("/api/v1/alert-rules").contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		when(api.disable(admin, id)).thenThrow(new AlertException(ErrorCode.RULE_NOT_FOUND));
		mvc.perform(delete("/api/v1/alert-rules/"+id)).andExpect(status().isNotFound());
	}
}
