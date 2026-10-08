package com.h.vanioak.api.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.CredentialPage;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.CredentialView;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.IssuedKey;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

class ApiKeyControllerTest {
	private final ApiKeyFacade keys = mock(ApiKeyFacade.class);
	private final UUID appId = UUID.randomUUID();
	private final UUID envId = UUID.randomUUID();
	private final UUID actor = UUID.randomUUID();
	private final UUID id = UUID.randomUUID();
	private final String scopePath = "/api/v1/applications/" + appId + "/environments/" + envId + "/api-keys";
	private final CredentialView credential = new CredentialView(id, envId, "public-prefix", Status.ACTIVE,
			null, Instant.parse("2026-01-01T00:00:00Z"), null);
	private final IssuedKey issued = new IssuedKey(credential, "test-only-secret");
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ApiKeyController(keys))
				.setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@Test
	void createSupportsOptionalBodyAndUsesAuthenticatedActor() throws Exception {
		when(keys.create(appId, envId, actor, null)).thenReturn(issued);
		for (String body : List.of("", "{}", "{\"expires_at\":null,\"created_by\":\"" + UUID.randomUUID() + "\"}")) {
			mvc.perform(post(scopePath).principal(() -> actor.toString()).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isCreated()).andExpect(jsonPath("$.*", hasSize(3)))
					.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Success"))
					.andExpect(jsonPath("$.data.*", hasSize(2))).andExpect(jsonPath("$.data.api_key").value("test-only-secret"))
					.andExpect(jsonPath("$.data.credential.*", hasSize(7)))
					.andExpect(jsonPath("$.data.credential.environment_id").value(envId.toString()))
					.andExpect(jsonPath("$.data.credential.key_prefix").value("public-prefix"))
					.andExpect(jsonPath("$.data.credential.created_at").isString())
					.andExpect(jsonPath("$.data.credential.expires_at").value(nullValue()))
					.andExpect(jsonPath("$.data.credential.key_hash").doesNotExist())
					.andExpect(jsonPath("$.data.credential.created_by").doesNotExist());
		}
		verify(keys, org.mockito.Mockito.times(3)).create(appId, envId, actor, null);
		Instant expiry = Instant.parse("2099-01-01T00:00:00Z");
		when(keys.create(appId, envId, actor, expiry)).thenReturn(issued);
		mvc.perform(post(scopePath).principal(() -> actor.toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"expires_at\":\"2099-01-01T00:00:00Z\"}")).andExpect(status().isCreated());
		verify(keys).create(appId, envId, actor, expiry);
	}

	@Test
	void listAndRevokeContainOnlyMetadataWhileRotateReturnsSecret() throws Exception {
		when(keys.list(appId, envId, "cursor", 2)).thenReturn(new CredentialPage(List.of(credential), null));
		mvc.perform(get(scopePath).param("cursor", "cursor").param("limit", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.*", hasSize(2)))
				.andExpect(jsonPath("$.data.next_cursor").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].*", hasSize(7)))
				.andExpect(jsonPath("$.data.items[0].api_key").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].key_hash").doesNotExist());
		verify(keys).list(appId, envId, "cursor", 2);
		when(keys.revoke(id)).thenReturn(credential);
		mvc.perform(delete("/api/v1/api-keys/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.*", hasSize(7))).andExpect(jsonPath("$.data.api_key").doesNotExist());
		verify(keys).revoke(id);
		when(keys.rotate(id, actor)).thenReturn(issued);
		mvc.perform(post("/api/v1/api-keys/" + id + "/rotate").principal(() -> actor.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.api_key").value("test-only-secret"));
		verify(keys).rotate(id, actor);
	}

	@Test
	void invalidUuidAndDateDoNotInvokeFacade() throws Exception {
		for (String body : List.of("{", "{\"expires_at\":\"not-a-date\"}")) {
			mvc.perform(post(scopePath).principal(() -> actor.toString()).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
		}
		mvc.perform(delete("/api/v1/api-keys/invalid")).andExpect(status().isBadRequest());
		mvc.perform(get(scopePath).param("limit", "invalid")).andExpect(status().isBadRequest());
		verifyNoInteractions(keys);
	}

	@Test
	void businessErrorsAndUnexpectedFailuresUseExistingSafeEnvelope() throws Exception {
		when(keys.rotate(id, actor)).thenThrow(new IdentityException(ErrorCode.ACCESS_DENIED));
		mvc.perform(post("/api/v1/api-keys/" + id + "/rotate").principal(() -> actor.toString()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access denied"))
				.andExpect(jsonPath("$.data").value(nullValue()));
		when(keys.revoke(id)).thenThrow(new IdentityException(ErrorCode.API_KEY_NOT_FOUND));
		mvc.perform(delete("/api/v1/api-keys/" + id)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("API key not found"));
		when(keys.list(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("Test internal details"));
		mvc.perform(get(scopePath)).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}
}

