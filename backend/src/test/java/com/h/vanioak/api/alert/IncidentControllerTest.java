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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.h.vanioak.api.alert.dto.IncidentResponse;
import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Filters;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;

class IncidentControllerTest {
	final IncidentApiService api=mock(IncidentApiService.class);
	final UUID actor=UUID.randomUUID(),app=UUID.randomUUID(),env=UUID.randomUUID(),id=UUID.randomUUID();
	final org.springframework.security.core.Authentication caller=UsernamePasswordAuthenticationToken.authenticated(actor.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_ENGINEER")));
	MockMvc mvc;
	IncidentResponse response(List<IncidentResponse.History> history) {
		return new IncidentResponse(id,new IncidentResponse.Ref(app,"app"),new IncidentResponse.Ref(env,"TEST"),Level.ERROR,Status.OPEN,
				"a".repeat(64),"message",Long.MAX_VALUE,Instant.EPOCH,Instant.EPOCH,null,history);
	}
	@BeforeEach void setup() {
		mvc=MockMvcBuilders.standaloneSetup(new IncidentController(api)).defaultRequest(get("/").principal(caller))
				.setControllerAdvice(new IncidentExceptionHandler(),new GlobalExceptionHandler()).build();
	}
	@Test void listUsesEnvelopeReferencesAndOmitsHistory() throws Exception {
		when(api.list(eq(caller),isNull(),isNull(),any())).thenReturn(new IncidentResponse.Page(List.of(response(null)),"next"));
		mvc.perform(get("/api/v1/incidents")).andExpect(status().isOk()).andExpect(jsonPath("$.*",hasSize(3)))
				.andExpect(jsonPath("$.data.items[0].application.id").value(app.toString())).andExpect(jsonPath("$.data.items[0].environment.name").value("TEST"))
				.andExpect(jsonPath("$.data.items[0].events").doesNotExist()).andExpect(jsonPath("$.data.items[0].last_seen_at").exists())
				.andExpect(jsonPath("$.data.next_cursor").value("next"));
		verify(api).list(caller,null,null,new Filters(null,null,null,null,null,50));
	}
	@Test void combinesExactlyDocumentedFilters() throws Exception {
		when(api.list(eq(caller),eq(app),eq(env),any())).thenReturn(new IncidentResponse.Page(List.of(),null));
		mvc.perform(get("/api/v1/incidents").param("application_id",app.toString()).param("environment_id",env.toString()).param("status","RESOLVED")
				.param("level","CRITICAL").param("from","2026-10-27T07:00:00+07:00").param("to","2026-10-28T00:00:00Z").param("cursor","token").param("limit","2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items",hasSize(0)));
		var capture=ArgumentCaptor.forClass(Filters.class);verify(api).list(eq(caller),eq(app),eq(env),capture.capture());
		assertEquals(Status.RESOLVED,capture.getValue().status());assertEquals(Level.CRITICAL,capture.getValue().level());
		assertEquals(Instant.parse("2026-10-27T00:00:00Z"),capture.getValue().from());assertEquals("token",capture.getValue().cursor());
	}
	@Test void explicitApplicationAndEnvironmentWithOptionalFiltersOmitted() throws Exception {
		when(api.list(eq(caller),eq(app),eq(env),any())).thenReturn(new IncidentResponse.Page(List.of(response(null)),null));
		mvc.perform(get("/api/v1/incidents").param("application_id",app.toString()).param("environment_id",env.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items",hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].events").doesNotExist());
		verify(api).list(caller,app,env,new Filters(null,null,null,null,null,50));
	}
	@Test void detailIncludesHistoryAndNullableFields() throws Exception {
		var event=new IncidentResponse.History(UUID.randomUUID(),"RESOLVED",Instant.EPOCH,4L,null,null);
		when(api.detail(caller,id)).thenReturn(response(List.of(event)));
		mvc.perform(get("/api/v1/incidents/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.events",hasSize(1)))
				.andExpect(jsonPath("$.data.events[0].event_type").value("RESOLVED")).andExpect(jsonPath("$.data.events[0].event_at").exists())
				.andExpect(jsonPath("$.data.events[0].metadata").value(org.hamcrest.Matchers.nullValue()));
	}
	@Test void malformedInputsFailBeforeApiService() throws Exception {
		for(var pair:List.of(new String[]{"status","OTHER"},new String[]{"level","INFO"},new String[]{"from","bad-time"},new String[]{"limit","bad"},new String[]{"environment_id","bad"}))
			mvc.perform(get("/api/v1/incidents").param(pair[0],pair[1])).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		mvc.perform(get("/api/v1/incidents/bad-uuid")).andExpect(status().isBadRequest());verifyNoInteractions(api);
	}
	@Test void authorizationAndMissingErrorsStaySanitized() throws Exception {
		when(api.detail(caller,id)).thenThrow(new AlertException(ErrorCode.ACCESS_DENIED));
		mvc.perform(get("/api/v1/incidents/"+id)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		doThrow(new AlertException(ErrorCode.INCIDENT_NOT_FOUND)).when(api).detail(caller,id);
		mvc.perform(get("/api/v1/incidents/"+id)).andExpect(status().isNotFound());
	}
}
