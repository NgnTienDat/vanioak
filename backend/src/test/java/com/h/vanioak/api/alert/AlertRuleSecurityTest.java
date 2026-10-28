package com.h.vanioak.api.alert;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

@WebMvcTest(AlertRuleController.class)
@Import({AlertRuleSecurityConfig.class, IdentitySecurityConfig.class})
class AlertRuleSecurityTest {
	@Autowired MockMvc mvc;
	@MockitoBean AuthFacade auth;
	@MockitoBean AlertRuleApiService api;
	final UUID actor = UUID.randomUUID(), id = UUID.randomUUID(), env = UUID.randomUUID();
	final String path = "/api/v1/alert-rules";
	@BeforeEach void setup() {
		when(auth.authenticateAccessToken("admin")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ADMIN"));
		when(auth.authenticateAccessToken("engineer")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		var view = new AlertRuleFacade.Rule(id, env, AlertRuleFacade.Level.ERROR, 1, 1, 1, 1, true, Instant.EPOCH, Instant.EPOCH);
		when(api.list(any(), any(), anyInt())).thenReturn(new AlertRuleFacade.Page(List.of(), null));
		when(api.create(any(), any())).thenReturn(view); when(api.update(any(), any(), any())).thenReturn(view); when(api.disable(any(), any())).thenReturn(view);
	}

	@Test void adminCanUseExactlyExistingOperations() throws Exception {
		String body="{\"environment_id\":\""+env+"\",\"level\":\"ERROR\",\"threshold\":1,\"window_seconds\":1,\"cooldown_seconds\":1,\"resolve_after_seconds\":1,\"enabled\":true}";
		mvc.perform(get(path).header("Authorization", "Bearer admin")).andExpect(status().isOk());
		mvc.perform(post(path).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		mvc.perform(patch(path+"/"+id).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isOk());
		mvc.perform(delete(path+"/"+id).header("Authorization", "Bearer admin")).andExpect(status().isOk());
	}

	@Test void engineerCannotReadOrWriteRulesEvenWithForgedAdminHeaders() throws Exception {
		for (var request : List.of(get(path),post(path),patch(path+"/"+id),delete(path+"/"+id)))
			mvc.perform(request.header("Authorization", "Bearer engineer").header("X-Role", "ADMIN").header("X-User-Id", actor.toString()))
					.andExpect(status().isForbidden());
		verifyNoInteractions(api);
	}

	@Test void missingApiKeyOnlyAndInvalidBearerAreUnauthorized() throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
		mvc.perform(get(path).header("X-API-Key", "not-human-auth")).andExpect(status().isUnauthorized());
		when(auth.authenticateAccessToken("bad")).thenThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		mvc.perform(get(path).header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized()); verifyNoInteractions(api);
	}

	@Test void disabledAndRevokedAuthenticationFailsBeforeAlert() throws Exception {
		when(auth.authenticateAccessToken("admin")).thenThrow(new IdentityException(ErrorCode.ACCESS_DENIED));
		mvc.perform(get(path).header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
		doThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS)).when(auth).authenticateAccessToken("admin");
		mvc.perform(delete(path+"/"+id).header("Authorization", "Bearer admin")).andExpect(status().isUnauthorized()); verifyNoInteractions(api);
	}

	@Test void unimplementedOperationsAndIncidentWritesStayDenied() throws Exception {
		for (var request : List.of(get(path+"/"+id),put(path),get(path+"/"+id+"/other"),post("/api/v1/incidents/"+id+"/resolve")))
			mvc.perform(request.header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
		verifyNoInteractions(api);
	}
}
