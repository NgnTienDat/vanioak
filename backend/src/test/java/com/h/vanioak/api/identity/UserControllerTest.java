package com.h.vanioak.api.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.api.UserFacade.CreateUser;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.api.UserFacade.UpdateUser;
import com.h.vanioak.modules.identity.api.UserFacade.UserPage;
import com.h.vanioak.modules.identity.api.UserFacade.UserView;

class UserControllerTest {

	private final UserFacade users = mock(UserFacade.class);
	private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private final UserView active = new UserView(id, "engineer", "ENGINEER", Status.ACTIVE);
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new UserController(users))
				.setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@Test
	void happyPathsUseEnvelopeAndNeverReturnPasswords() throws Exception {
		when(users.create(any())).thenReturn(active);
		when(users.get(id)).thenReturn(active);
		when(users.update(eq(id), any())).thenReturn(active);
		when(users.disable(id)).thenReturn(new UserView(id, "engineer", "ENGINEER", Status.DISABLED));
		when(users.list(null, null, 50)).thenReturn(new UserPage(List.of(active), null));
		String json = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"engineer\",\"password\":\"secret-password\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data.role").value("ENGINEER"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.password").doesNotExist())
				.andExpect(jsonPath("$.data.password_hash").doesNotExist()).andReturn().getResponse().getContentAsString();
		assertFalse(json.contains("secret-password"));
		mvc.perform(get("/api/v1/users/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()));
		mvc.perform(get("/api/v1/users")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].username").value("engineer"))
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("next_cursor")))
				.andExpect(jsonPath("$.data.next_cursor").value(org.hamcrest.Matchers.nullValue()));
		mvc.perform(patch("/api/v1/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
		verify(users).update(id, new UpdateUser(null, null, Status.ACTIVE));
		mvc.perform(delete("/api/v1/users/{id}", id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DISABLED"));
		verify(users).create(new CreateUser("engineer", "secret-password"));
	}

	@Test
	void businessErrorsUseStatusAndSafeMessageFromErrorCode() throws Exception {
		for (ErrorCode code : List.of(ErrorCode.USER_NOT_FOUND, ErrorCode.ADMIN_USER_FORBIDDEN,
				ErrorCode.USERNAME_ALREADY_EXISTS)) {
			doThrow(new IdentityException(code)).when(users).get(id);
			mvc.perform(get("/api/v1/users/{id}", id)).andExpect(status().is(code.getStatus().value()))
					.andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value(code.getMessage()))
					.andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("data")))
					.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
		}
	}

	@Test
	void rejectsInvalidCreateAndUnknownFieldsButAllowsUsernameLength100() throws Exception {
		for (String body : List.of("{}", "{\"username\":\" \"}", "{\"username\":\"name\",\"password\":null}",
				"{\"username\":\"name\",\"password\":\"\"}",
				"{\"username\":\"" + "x".repeat(101) + "\",\"password\":\"secret-password\"}",
				"{\"username\":\"name\",\"password\":\"secret-password\",\"role\":\"ADMIN\"}",
				"{\"username\":\"name\",\"password\":\"secret-password\",\"status\":\"ACTIVE\"}")) {
			String json = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
					.andReturn().getResponse().getContentAsString();
			assertFalse(json.contains("secret-password"));
		}
		when(users.create(any())).thenReturn(active);
		mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + "x".repeat(100) + "\",\"password\":\"password\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void rejectsInvalidPatchJsonUuidAndQueryParameters() throws Exception {
		for (String body : List.of("{}", "{\"role\":\"ADMIN\"}", "{\"status\":\"UNKNOWN\"}", "{\"status\":null}",
				"{\"username\":\" \"}", "{\"password\":\"\"}", "{\"password\":null}", "{broken")) {
			mvc.perform(patch("/api/v1/users/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		}
		mvc.perform(get("/api/v1/users/not-a-uuid")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users/1-1-1-1-1")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users").param("limit", "invalid")).andExpect(status().isBadRequest());
		when(users.list(null, "invalid", 50)).thenThrow(new IdentityException(ErrorCode.INVALID_CURSOR));
		mvc.perform(get("/api/v1/users").param("cursor", "invalid")).andExpect(status().isBadRequest());
	}

	@Test
	void passesStatusCursorAndLimitToFacade() throws Exception {
		when(users.list(Status.DISABLED, "cursor", 1)).thenReturn(new UserPage(List.of(), null));
		mvc.perform(get("/api/v1/users").param("status", "DISABLED").param("cursor", "cursor").param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
		verify(users).list(Status.DISABLED, "cursor", 1);
	}
}
