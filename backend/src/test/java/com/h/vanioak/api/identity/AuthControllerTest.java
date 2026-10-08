package com.h.vanioak.api.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.AuthFacade.LoginResult;
import com.h.vanioak.modules.identity.api.AuthFacade.UserSummary;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

class AuthControllerTest {

	private final AuthFacade auth = mock(AuthFacade.class);
	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private final LoginResult result = new LoginResult("access-value", "refresh-value", "Bearer", 900,
			new UserSummary(userId, "admin", "ADMIN"));
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
				.setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@Test
	void loginPreservesCredentialsAndReturnsExactContract() throws Exception {
		when(auth.login(" admin ", " password ")).thenReturn(result);
		request("login", "{\"username\":\" admin \",\"password\":\" password \"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data.*", hasSize(5)))
				.andExpect(jsonPath("$.data.access_token").value("access-value"))
				.andExpect(jsonPath("$.data.refresh_token").value("refresh-value"))
				.andExpect(jsonPath("$.data.token_type").value("Bearer"))
				.andExpect(jsonPath("$.data.expires_in").value(900))
				.andExpect(jsonPath("$.data.user.*", hasSize(3)))
				.andExpect(jsonPath("$.data.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.data.user.username").value("admin"))
				.andExpect(jsonPath("$.data.user.role").value("ADMIN"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		verify(auth).login(" admin ", " password ");
	}

	@Test
	void refreshUsesJsonCredentialAndOmitsUser() throws Exception {
		when(auth.refresh("current-refresh")).thenReturn(result);
		request("refresh", "{\"refresh_token\":\"current-refresh\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data.*", hasSize(4)))
				.andExpect(jsonPath("$.data.access_token").value("access-value"))
				.andExpect(jsonPath("$.data.refresh_token").value("refresh-value"))
				.andExpect(jsonPath("$.data.token_type").value("Bearer"))
				.andExpect(jsonPath("$.data.expires_in").value(900))
				.andExpect(jsonPath("$.data.user").doesNotExist())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		verify(auth).refresh("current-refresh");
	}

	@Test
	void logoutForwardsOptionalBearerWithoutVerifyingIt() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "bEaReR  not-a-jwt ")
				.content("{\"refresh_token\":\"current-refresh\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
				.andExpect(jsonPath("$.data").value((Object) null))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		verify(auth).logout("current-refresh", "not-a-jwt");
	}

	@Test
	void logoutWithoutUsableBearerPassesNull() throws Exception {
		request("logout", "{\"refresh_token\":\"missing-header\"}").andExpect(status().isOk());
		verify(auth).logout("missing-header", null);
		for (String authorization : new String[] {"", "Basic credential", "Bearer", "Bearer   "}) {
			mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, authorization)
					.content("{\"refresh_token\":\"" + authorization + "\"}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data").value((Object) null));
			verify(auth).logout(authorization, null);
		}
	}

	@Test
	void loginValidatesOnlyRequiredFieldsAndUsernameMaximum() throws Exception {
		for (String body : new String[] {"{}", "{\"username\":null,\"password\":null}",
				"{\"username\":\"admin\"}", "{\"password\":\"password\"}",
				"{\"username\":\"" + "a".repeat(101) + "\",\"password\":\"private-value\"}"}) {
			String response = request("login", body).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value("Validation failed"))
					.andExpect(jsonPath("$.data").isMap()).andReturn().getResponse().getContentAsString();
			assertFalse(response.contains("private-value"));
		}
		verifyNoInteractions(auth);
	}

	@Test
	void refreshAndLogoutRequireJsonRefreshToken() throws Exception {
		for (String operation : new String[] {"refresh", "logout"}) {
			for (String body : new String[] {"{}", "{\"refresh_token\":null}"}) {
				request(operation, body).andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.success").value(false))
						.andExpect(jsonPath("$.message").value("Validation failed"))
						.andExpect(jsonPath("$.data.refreshToken").isString());
			}
		}
		verifyNoInteractions(auth);
	}

	@Test
	void missingOrMalformedBodiesUseExistingSafe400Handler() throws Exception {
		for (String operation : new String[] {"login", "refresh", "logout"}) {
			for (String body : new String[] {"", "{", "null"}) {
				request(operation, body).andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.success").value(false))
						.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()))
						.andExpect(jsonPath("$.data").value((Object) null));
			}
		}
		verifyNoInteractions(auth);
	}

	@Test
	void blankInputsAndMaximumLengthUsernameAreDelegated() throws Exception {
		when(auth.login("", "")).thenReturn(result);
		when(auth.login("a".repeat(100), " ")).thenReturn(result);
		when(auth.refresh(" ")).thenReturn(result);
		request("login", "{\"username\":\"\",\"password\":\"\"}").andExpect(status().isOk());
		request("login", "{\"username\":\"" + "a".repeat(100) + "\",\"password\":\" \"}")
				.andExpect(status().isOk());
		request("refresh", "{\"refresh_token\":\" \"}").andExpect(status().isOk());
		request("logout", "{\"refresh_token\":\"\"}").andExpect(status().isOk());
		verify(auth).login("", "");
		verify(auth).login("a".repeat(100), " ");
		verify(auth).refresh(" ");
		verify(auth).logout("", null);
	}

	@Test
	void business401UsesIdentityStatusMessageAndEnvelopeForAllEndpoints() throws Exception {
		when(auth.login(anyString(), anyString())).thenThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		when(auth.refresh(anyString())).thenThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		doThrow(new IdentityException(ErrorCode.INVALID_CREDENTIALS)).when(auth).logout("refresh-value", null);
		for (String operation : new String[] {"login", "refresh", "logout"}) {
			request(operation, body(operation)).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.*", hasSize(3)))
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_CREDENTIALS.getMessage()))
					.andExpect(jsonPath("$.data").value((Object) null));
		}
	}

	@Test
	void unexpectedFailuresReturnGeneric500WithoutInternalDetails() throws Exception {
		var failure = new IllegalStateException("private-infrastructure-details");
		when(auth.login(anyString(), anyString())).thenThrow(failure);
		when(auth.refresh(anyString())).thenThrow(failure);
		doThrow(failure).when(auth).logout("refresh-value", null);
		for (String operation : new String[] {"login", "refresh", "logout"}) {
			String response = request(operation, body(operation)).andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.*", hasSize(3)))
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
					.andExpect(jsonPath("$.data").value((Object) null))
					.andReturn().getResponse().getContentAsString();
			assertFalse(response.contains("private-infrastructure-details"));
			assertFalse(response.contains("refresh-value"));
			assertFalse(response.contains("password-value"));
		}
	}

	private ResultActions request(String operation, String body) throws Exception {
		return mvc.perform(post("/api/v1/auth/" + operation).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String body(String operation) {
		return operation.equals("login") ? "{\"username\":\"admin\",\"password\":\"password-value\"}"
				: "{\"refresh_token\":\"refresh-value\"}";
	}
}
