package com.h.vanioak.api.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade.ApplicationPage;
import com.h.vanioak.modules.identity.api.ApplicationFacade.ApplicationView;
import com.h.vanioak.modules.identity.api.ApplicationFacade.CreateApplication;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentView;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade.UpdateApplication;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

class ApplicationControllerTest {
	private final ApplicationFacade applications = mock(ApplicationFacade.class);
	private final UUID id = UUID.randomUUID();
	private final ApplicationView view = new ApplicationView(id, " app ", null, Status.ACTIVE,
			List.of(new EnvironmentView(UUID.randomUUID(), EnvironmentName.DEV, Status.ACTIVE),
					new EnvironmentView(UUID.randomUUID(), EnvironmentName.TEST, Status.ACTIVE),
					new EnvironmentView(UUID.randomUUID(), EnvironmentName.STAGING, Status.ACTIVE)));
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(applications))
				.setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@Test
	void createAndListUseContractShapeAndDelegateUnchangedInput() throws Exception {
		when(applications.create(new CreateApplication(" app ", null))).thenReturn(view);
		mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" app \"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data.*", hasSize(5))).andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.environments", hasSize(3)))
				.andExpect(jsonPath("$.data.environments[0].*", hasSize(3)))
				.andExpect(jsonPath("$.data.environments[0].name").value("DEV"));
		verify(applications).create(new CreateApplication(" app ", null));
		when(applications.list(Status.DISABLED, "opaque", 2)).thenReturn(new ApplicationPage(List.of(view), null));
		mvc.perform(get("/api/v1/applications").param("status", "DISABLED").param("cursor", "opaque").param("limit", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.*", hasSize(2)))
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.next_cursor").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.nextCursor").doesNotExist());
		verify(applications).list(Status.DISABLED, "opaque", 2);
	}

	@Test
	void updateSupportsPartialChangesAndEmptyObjectWithoutInventedConstraints() throws Exception {
		when(applications.update(id, new UpdateApplication(null, "", Status.DISABLED))).thenReturn(view);
		mvc.perform(patch("/api/v1/applications/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"description\":\"\",\"status\":\"DISABLED\"}")).andExpect(status().isOk());
		verify(applications).update(id, new UpdateApplication(null, "", Status.DISABLED));
		when(applications.update(id, new UpdateApplication(null, null, null))).thenReturn(view);
		mvc.perform(patch("/api/v1/applications/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isOk());
		when(applications.create(any())).thenReturn(view);
		for (String name : List.of("", "a".repeat(100))) {
			mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated());
		}
	}

	@Test
	void invalidRequestsDoNotInvokeFacade() throws Exception {
		for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"" + "a".repeat(101) + "\"}",
				"{\"name\":\"app\",\"description\":null}", "{")) {
			mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		}
		for (String body : List.of("{\"status\":null}", "{\"status\":\"OTHER\"}", "{\"name\":null}",
				"{\"description\":null}", "{\"name\":\"" + "a".repeat(101) + "\"}")) {
			mvc.perform(patch("/api/v1/applications/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(patch("/api/v1/applications/invalid").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/applications").param("status", "OTHER")).andExpect(status().isBadRequest());
		verifyNoInteractions(applications);
	}

	@Test
	void existingAdviceReturnsBusinessStatusAndSafeUnexpectedError() throws Exception {
		when(applications.create(any())).thenThrow(new IdentityException(ErrorCode.INVALID_REQUEST));
		mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"duplicate\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
		when(applications.update(any(), any())).thenThrow(new IdentityException(ErrorCode.APPLICATION_NOT_FOUND));
		mvc.perform(patch("/api/v1/applications/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Application not found"))
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		when(applications.list(any(), any(), anyInt())).thenThrow(new IllegalStateException("internal test detail"));
		mvc.perform(get("/api/v1/applications")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
	}
}

