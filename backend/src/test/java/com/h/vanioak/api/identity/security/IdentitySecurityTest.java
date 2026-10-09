package com.h.vanioak.api.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import com.h.vanioak.api.identity.AuthController;
import com.h.vanioak.api.identity.UserController;
import com.h.vanioak.api.identity.ApplicationController;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.api.identity.ApiKeyController;
import com.h.vanioak.api.ingestion.IngestionController;
import com.h.vanioak.api.ingestion.security.IngestionSecurityConfig;
import com.h.vanioak.modules.ingestion.internal.IngestionService;
import com.h.vanioak.modules.ingestion.internal.RawLogPublisher;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade.LoginResult;
import com.h.vanioak.modules.identity.api.AuthFacade.UserSummary;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.api.UserFacade.UserPage;
import com.h.vanioak.modules.identity.internal.auth.AccessTokenBlacklist;
import com.h.vanioak.modules.identity.internal.auth.AuthService;
import com.h.vanioak.modules.identity.internal.auth.JwtService;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@WebMvcTest({UserController.class, AuthController.class, ApplicationController.class, ApiKeyController.class, IngestionController.class})
@Import({IdentitySecurityConfig.class, IngestionSecurityConfig.class, IngestionService.class,
		SecurityConfig.class, AuthService.class, JwtService.class})
class IdentitySecurityTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JwtService jwt;
	@Value("${security.jwt.access-secret}")
	private String accessSecret;
	@MockitoSpyBean
	private AuthService auth;
	@MockitoBean
	private UserRepository users;
	@MockitoBean
	private AccessTokenBlacklist blacklist;
	@MockitoBean
	private RefreshTokenRepository refreshTokens;
	@MockitoBean
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private UserFacade management;
	@MockitoBean
	private ApplicationFacade applications;
	@MockitoBean
	private ApiKeyFacade keys;
	@MockitoBean
	private RawLogPublisher publisher;
	private final UUID userId = UUID.randomUUID();
	private UserEntity user;
	private String token;

	@BeforeEach
	void setup() {
		user = mock(UserEntity.class);
		when(user.getId()).thenReturn(userId);
		when(user.getRole()).thenReturn("ADMIN");
		when(user.getStatus()).thenReturn(Status.ACTIVE);
		when(users.findById(userId)).thenReturn(Optional.of(user));
		when(management.list(null, null, 50)).thenReturn(new UserPage(List.of(), null));
		token = jwt.generateAccessToken(userId);
	}

	@Test
	void humanJwtCannotSubstituteForIngestionApiKey() throws Exception {
		when(keys.verify("test-only-key")).thenReturn(new ApiKeyFacade.VerificationResult(false, null, null, null, null, null));
		for (String path : List.of("/api/v1/logs", "/api/v1/logs/batch")) {
			failure(mvc.perform(post(path).header("X-API-Key", "test-only-key")
					.contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "Invalid API key");
			failure(mvc.perform(post(path).header("X-API-Key", "test-only-key")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "Invalid API key");
		}
	}

	@Test
	void apiKeyOperationsUseCurrentAdminPrincipalAndRejectOtherCallers() throws Exception {
		UUID appId = UUID.randomUUID();
		UUID envId = UUID.randomUUID();
		UUID credentialId = UUID.randomUUID();
		String scope = "/api/v1/applications/" + appId + "/environments/" + envId + "/api-keys";
		String keyPath = "/api/v1/api-keys/" + credentialId;
		var credential = new ApiKeyFacade.CredentialView(credentialId, envId, "public-prefix",
				ApiKeyFacade.Status.ACTIVE, null, Instant.now(), null);
		var issued = new ApiKeyFacade.IssuedKey(credential, "test-only-key");
		when(keys.list(appId, envId, null, 50)).thenReturn(new ApiKeyFacade.CredentialPage(List.of(), null));
		when(keys.create(appId, envId, userId, null)).thenReturn(issued);
		when(keys.rotate(credentialId, userId)).thenReturn(issued);
		when(keys.revoke(credentialId)).thenReturn(credential);
		failure(mvc.perform(get(scope)), 401, "Invalid credentials");
		mvc.perform(get(scope).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(post(scope).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isCreated());
		mvc.perform(post(keyPath + "/rotate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(delete(keyPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		verify(keys).create(appId, envId, userId, null);
		verify(keys).rotate(credentialId, userId);
		org.mockito.Mockito.clearInvocations(keys);
		when(user.getRole()).thenReturn("ENGINEER");
		failure(mvc.perform(get(scope).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		failure(mvc.perform(post(scope).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		failure(mvc.perform(post(keyPath + "/rotate").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		failure(mvc.perform(delete(keyPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		when(user.getRole()).thenReturn("ADMIN");
		when(user.getStatus()).thenReturn(Status.DISABLED);
		failure(mvc.perform(get(scope).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		verifyNoInteractions(keys);
	}

	@Test
	void applicationOperationsAreAdminOnlyAndFutureRoutesRemainDenied() throws Exception {
		var application = new ApplicationFacade.ApplicationView(UUID.randomUUID(), "app", null,
				ApplicationFacade.Status.ACTIVE, List.of());
		when(applications.list(null, null, 50)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), null));
		when(applications.create(any())).thenReturn(application);
		when(applications.update(any(), any())).thenReturn(application);
		failure(mvc.perform(get("/api/v1/applications")), 401, "Invalid credentials");
		failure(mvc.perform(get("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer invalid")),
				401, "Invalid credentials");
		verifyNoInteractions(applications);
		mvc.perform(get("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"app\"}")).andExpect(status().isCreated());
		mvc.perform(patch("/api/v1/applications/" + application.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
		org.mockito.Mockito.clearInvocations(applications);
		when(user.getRole()).thenReturn("ENGINEER");
		failure(mvc.perform(get("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
				403, "Access denied");
		failure(mvc.perform(post("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"app\"}")), 403, "Access denied");
		failure(mvc.perform(patch("/api/v1/applications/" + application.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{}")), 403, "Access denied");
		when(user.getRole()).thenReturn("ADMIN");
		when(user.getStatus()).thenReturn(Status.DISABLED);
		failure(mvc.perform(get("/api/v1/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
				403, "Access denied");
		when(user.getStatus()).thenReturn(Status.ACTIVE);
		failure(mvc.perform(post("/api/v1/applications/" + application.id() + "/engineers/" + userId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "Access denied");
		verifyNoInteractions(applications);
	}

	@Test
	void missingNonBearerAndEmptyCredentialsReturn401() throws Exception {
		failure(mvc.perform(get("/api/v1/users")), 401, "Invalid credentials");
		for (String authorization : List.of("Basic value", "Bearer", "Bearer   ")) {
			failure(mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, authorization)),
					401, "Invalid credentials");
		}
		verifyNoInteractions(users, blacklist, management);
	}

	@Test
	void invalidExpiredRefreshAndBlacklistedTokensReturn401() throws Exception {
		for (String credential : List.of("malformed", expiredToken(),
				jwt.generateRefreshToken(userId, UUID.randomUUID(), UUID.randomUUID()))) {
			failure(protectedRequest(credential), 401, "Invalid credentials");
		}
		verifyNoInteractions(users, blacklist);
		when(blacklist.isBlacklisted(any())).thenReturn(true);
		failure(protectedRequest(token), 401, "Invalid credentials");
		verifyNoInteractions(users, management);
	}

	@Test
	void currentDatabaseRoleControlsAuthorizationForTheSameJwt() throws Exception {
		protectedRequest(token).andExpect(status().isOk());
		when(user.getRole()).thenReturn("ENGINEER");
		failure(protectedRequest(token), 403, "Access denied");
	}

	@Test
	void missingAndDisabledCurrentUsersAreRejected() throws Exception {
		when(users.findById(userId)).thenReturn(Optional.empty());
		failure(protectedRequest(token), 401, "Invalid credentials");
		when(users.findById(userId)).thenReturn(Optional.of(user));
		when(user.getStatus()).thenReturn(Status.DISABLED);
		failure(protectedRequest(token), 403, "Access denied");
		verifyNoInteractions(management);
	}

	@Test
	void redisAndDatabaseFailuresFailClosedWithSafe500() throws Exception {
		when(blacklist.isBlacklisted(any())).thenThrow(new IllegalStateException("private Redis details"));
		failure(protectedRequest(token), 500, "An unexpected error occurred");
		verifyNoInteractions(users, management);
		doReturn(false).when(blacklist).isBlacklisted(any());
		when(users.findById(userId)).thenThrow(new IllegalStateException("private database details"));
		failure(protectedRequest(token), 500, "An unexpected error occurred");
		verifyNoInteractions(management);
	}

	@Test
	void authPostsBypassInvalidAndExpiredAccessCredentials() throws Exception {
		var pair = new LoginResult("access", "refresh", "Bearer", 900, new UserSummary(userId, "admin", "ADMIN"));
		doReturn(pair).when(auth).login("admin", "password");
		doReturn(pair).when(auth).refresh("body-refresh");
		doNothing().when(auth).logout(anyString(), anyString());
		for (String credential : List.of("malformed", expiredToken())) {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
					.content("{\"username\":\"admin\",\"password\":\"password\"}"))
					.andExpect(status().isOk());
			mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
					.content("{\"refresh_token\":\"body-refresh\"}"))
					.andExpect(status().isOk());
			mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
					.content("{\"refresh_token\":\"body-refresh\"}"))
					.andExpect(status().isOk());
			verify(auth).logout("body-refresh", credential);
		}
		verify(auth, times(2)).login("admin", "password");
		verify(auth, times(2)).refresh("body-refresh");
		verifyNoInteractions(users, blacklist, refreshTokens, transactionManager);
	}

	@Test
	void authenticationUsesUuidNameAndIsNotRetainedInASession() throws Exception {
		when(management.list(null, null, 50)).thenAnswer(invocation -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			assertEquals(userId.toString(), authentication.getName());
			assertNull(authentication.getCredentials());
			assertEquals(List.of("ROLE_ADMIN"), authentication.getAuthorities().stream().map(Object::toString).toList());
			return new UserPage(List.of(), null);
		});
		var result = protectedRequest(token).andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		assertNull(result.getRequest().getSession(false));
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		verify(auth).authenticateAccessToken(token);
		verify(users).findById(userId);
		failure(mvc.perform(get("/api/v1/users")), 401, "Invalid credentials");
	}

	@Test
	void unmatchedRoutesAndNormalErrorRequestsRemainDenied() throws Exception {
		for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
				"/api/v1/auth/future", "/error")) {
			failure(mvc.perform(get(path)), 401, "Invalid credentials");
			failure(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
					403, "Access denied");
		}
		failure(mvc.perform(post("/api/v1/auth/future")), 401, "Invalid credentials");
	}

	@Test
	void mvcErrorsAndErrorDispatchAreNotReplacedBySecurityDenials() throws Exception {
		when(management.list(any(), any(), anyInt())).thenThrow(new IllegalStateException("private MVC details"));
		failure(protectedRequest(token), 500, "An unexpected error occurred");
		mvc.perform(get("/api/v1/users").param("limit", "invalid").with(request -> {
			request.setDispatcherType(DispatcherType.ERROR);
			return request;
		})
				.header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value("Invalid request"));
	}

	@Nested
	@TestPropertySource(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
	class SwaggerDisabled {
		@Autowired
		private MockMvc disabledMvc;

		@Test
		void documentationPathsAreNotPublicWhenDisabled() throws Exception {
			for (String path : List.of("/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html")) {
				failure(disabledMvc.perform(get(path)), 401, "Invalid credentials");
			}
		}
	}

	private ResultActions protectedRequest(String credential) throws Exception {
		return mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential));
	}

	private void failure(ResultActions response, int expectedStatus, String message) throws Exception {
		response.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.data").value((Object) null));
	}

	private String expiredToken() {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).id(UUID.randomUUID().toString())
				.issuedAt(Date.from(now.minusSeconds(120))).expiration(Date.from(now.minusSeconds(60)))
				.claim("token_use", "access")
				.signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(accessSecret)), Jwts.SIG.HS256).compact();
	}
}
