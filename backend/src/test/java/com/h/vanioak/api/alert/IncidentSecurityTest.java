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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.h.vanioak.api.alert.dto.IncidentResponse;
import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

@WebMvcTest(IncidentController.class)
@Import({IncidentSecurityConfig.class,IdentitySecurityConfig.class,AlertRuleSecurityConfig.class})
class IncidentSecurityTest {
	@Autowired MockMvc mvc;
	@MockitoBean IncidentApiService api;
	@MockitoBean AuthFacade auth;
	final UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),app=UUID.randomUUID(),env=UUID.randomUUID();
	@BeforeEach void setup() {
		when(auth.authenticateAccessToken("engineer")).thenReturn(new AuthFacade.AuthenticatedUser(actor,"ENGINEER"));
		when(auth.authenticateAccessToken("admin")).thenReturn(new AuthFacade.AuthenticatedUser(actor,"ADMIN"));
		when(api.list(any(),any(),any(),any())).thenReturn(new IncidentResponse.Page(List.of(),null));
		when(api.detail(any(),eq(id))).thenReturn(new IncidentResponse(id,new IncidentResponse.Ref(app,"app"),new IncidentResponse.Ref(env,"DEV"),Level.ERROR,
				Status.OPEN,"a".repeat(64),"message",1,Instant.EPOCH,Instant.EPOCH,null,List.of()));
	}
	@Test void bothHumanRolesCanReachOnlyTheTwoReadOperations() throws Exception {
		for(String token:List.of("admin","engineer")) {
			mvc.perform(get("/api/v1/incidents").header("Authorization","Bearer "+token)).andExpect(status().isOk());
			mvc.perform(get("/api/v1/incidents/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
		}
	}
	@Test void missingApiKeyOnlyAndInvalidBearerCannotReachReads() throws Exception {
		mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/incidents/"+id).header("X-API-Key","key")).andExpect(status().isUnauthorized());
		when(auth.authenticateAccessToken("bad")).thenThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		mvc.perform(get("/api/v1/incidents").header("Authorization","Bearer bad")).andExpect(status().isUnauthorized());verifyNoInteractions(api);
	}
	@Test void disabledUserOrRevokedTokenFailsBeforeApi() throws Exception {
		doThrow(new IdentityException(ErrorCode.ACCESS_DENIED)).when(auth).authenticateAccessToken("engineer");
		mvc.perform(get("/api/v1/incidents").header("Authorization","Bearer engineer")).andExpect(status().isForbidden());
		doThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS)).when(auth).authenticateAccessToken("engineer");
		mvc.perform(get("/api/v1/incidents/"+id).header("Authorization","Bearer engineer")).andExpect(status().isUnauthorized());verifyNoInteractions(api);
	}
	@Test void writesManualResolutionAndSeparateHistoryEndpointStayDenied() throws Exception {
		for(var request:List.of(post("/api/v1/incidents"),patch("/api/v1/incidents/"+id),delete("/api/v1/incidents/"+id),post("/api/v1/incidents/"+id+"/resolve"),get("/api/v1/incidents/"+id+"/history")))
			mvc.perform(request.header("Authorization","Bearer admin")).andExpect(status().isForbidden());
		verifyNoInteractions(api);
	}
	@Test void engineerDoesNotGainAlertRulePrivilegesThroughIncidentChain() throws Exception {
		mvc.perform(get("/api/v1/alert-rules").header("Authorization","Bearer engineer").header("X-Role","ADMIN"))
				.andExpect(status().isForbidden());verifyNoInteractions(api);
	}
}
