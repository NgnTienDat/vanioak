package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import com.h.vanioak.modules.identity.api.AuthFacade.LoginResult;
import com.h.vanioak.modules.identity.api.AuthFacade.UserSummary;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

@SpringBootTest(classes = JwtService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthServiceTest {

	private static final UUID USER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
	private static final String USERNAME = "login-test-user";
	private static final String PASSWORD = " test-only-password ";

	@Autowired
	private JwtService jwt;
	private UserRepository users;
	private RefreshTokenRepository refreshTokens;
	private PasswordEncoder encoder;
	private String passwordHash;
	private AuthService service;

	@BeforeEach
	void setup() {
		users = mock(UserRepository.class);
		refreshTokens = mock(RefreshTokenRepository.class);
		encoder = spy(new BCryptPasswordEncoder());
		passwordHash = encoder.encode(PASSWORD);
		service = new AuthService(users, encoder, jwt, refreshTokens, mock(PlatformTransactionManager.class),
				mock(AccessTokenBlacklist.class));
	}

	@Test
	void activeAdminAndEngineerReceiveVerifiedTokensAndMatchingMetadata() {
		for (String role : new String[] { "ADMIN", "ENGINEER" }) {
			reset(users, refreshTokens, encoder);
			doReturn(Optional.of(user(USERNAME, role, Status.ACTIVE))).when(users).findByUsername(USERNAME);
			LoginResult result = service.login(USERNAME, PASSWORD);
			verify(encoder).matches(PASSWORD, passwordHash);
			verify(users).findByUsername(USERNAME);
			assertEquals(new UserSummary(USER_ID, USERNAME, role), result.user());
			assertEquals("Bearer", result.tokenType());
			assertEquals(900, result.expiresIn());
			var access = jwt.parseAccessToken(result.accessToken());
			var refresh = jwt.parseRefreshToken(result.refreshToken());
			assertEquals(USER_ID, UUID.fromString(access.getSubject()));
			assertEquals(USER_ID, UUID.fromString(refresh.getSubject()));
			assertEquals("access", access.get("token_use"));
			assertEquals("refresh", refresh.get("token_use"));
			var saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
			verify(refreshTokens).saveAndFlush(saved.capture());
			RefreshTokenEntity metadata = saved.getValue();
			assertEquals(UUID.fromString(refresh.getId()), metadata.getTokenId());
			assertEquals(UUID.fromString(refresh.get("family_id", String.class)), metadata.getFamilyId());
			assertEquals(USER_ID, metadata.getUserId());
			assertEquals(refresh.getExpiration().toInstant(), metadata.getExpiresAt());
			assertNull(metadata.getParentTokenId());
			assertFalse(metadata.isUsed());
			assertFalse(metadata.isRevoked());
			assertFalse(result.toString().contains(PASSWORD));
			assertFalse(result.toString().contains(passwordHash));
		}
		assertEquals(Set.of("accessToken", "refreshToken", "tokenType", "expiresIn", "user"),
				Arrays.stream(LoginResult.class.getRecordComponents()).map(component -> component.getName())
						.collect(Collectors.toSet()));
		assertEquals(Set.of("id", "username", "role"),
				Arrays.stream(UserSummary.class.getRecordComponents()).map(component -> component.getName())
						.collect(Collectors.toSet()));
	}

	@Test
	void repeatedLoginsCreateIndependentTokenIdsAndFamilies() {
		doReturn(Optional.of(user(USERNAME, "ENGINEER", Status.ACTIVE))).when(users).findByUsername(USERNAME);
		var first = jwt.parseRefreshToken(service.login(USERNAME, PASSWORD).refreshToken());
		var second = jwt.parseRefreshToken(service.login(USERNAME, PASSWORD).refreshToken());
		assertNotEquals(UUID.fromString(first.getId()), UUID.fromString(second.getId()));
		assertNotEquals(UUID.fromString(first.get("family_id", String.class)),
				UUID.fromString(second.get("family_id", String.class)));
	}

	@Test
	void missingWrongAndDisabledCredentialsShareSafeUnauthorizedError() {
		when(users.findByUsername(USERNAME)).thenReturn(Optional.empty());
		assertInvalidCredentials(PASSWORD);
		doReturn(Optional.of(user(USERNAME, "ENGINEER", Status.ACTIVE))).when(users).findByUsername(USERNAME);
		assertInvalidCredentials("wrong-test-password");
		assertInvalidCredentials("");
		doReturn(Optional.of(user(USERNAME, "ENGINEER", Status.DISABLED))).when(users).findByUsername(USERNAME);
		assertInvalidCredentials(PASSWORD);
		verifyNoInteractions(refreshTokens);
	}

	@Test
	void invalidRequiredFieldsAndOverlongUsernameFailBeforeLookup() {
		for (String[] input : new String[][] { { null, PASSWORD }, { USERNAME, null }, { "u".repeat(101), PASSWORD } }) {
			IdentityException error = assertThrows(IdentityException.class, () -> service.login(input[0], input[1]));
			assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
		}
		verifyNoInteractions(users, refreshTokens);
	}

	@Test
	void acceptsHundredCharacterUsernameAndDoesNotTrimCredentials() {
		String username = " " + "u".repeat(98) + " ";
		doReturn(Optional.of(user(username, "ENGINEER", Status.ACTIVE))).when(users).findByUsername(username);
		LoginResult result = service.login(username, PASSWORD);
		assertEquals(username, result.user().username());
		verify(users).findByUsername(username);
		verify(encoder).matches(PASSWORD, passwordHash);
	}

	@Test
	void lookupFailurePropagatesWithoutIssuingSession() {
		var failure = new DataAccessResourceFailureException("Test database unavailable");
		when(users.findByUsername(USERNAME)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.login(USERNAME, PASSWORD)));
		verifyNoInteractions(refreshTokens);
	}

	@Test
	void persistenceFailurePreventsSuccessfulLogin() {
		doReturn(Optional.of(user(USERNAME, "ENGINEER", Status.ACTIVE))).when(users).findByUsername(USERNAME);
		var failure = new DataAccessResourceFailureException("Test persistence failure");
		doThrow(failure).when(refreshTokens).saveAndFlush(any(RefreshTokenEntity.class));
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.login(USERNAME, PASSWORD)));
	}

	@Test
	void internalTokenFailureIsNotReportedAsInvalidCredentials() {
		doReturn(Optional.of(user(USERNAME, "ENGINEER", Status.ACTIVE))).when(users).findByUsername(USERNAME);
		JwtService failedJwt = mock(JwtService.class);
		var failure = new IllegalStateException("Test token generation failure");
		when(failedJwt.generateAccessToken(USER_ID)).thenThrow(failure);
		AuthService failedService = new AuthService(users, encoder, failedJwt, refreshTokens,
				mock(PlatformTransactionManager.class), mock(AccessTokenBlacklist.class));
		assertSame(failure, assertThrows(IllegalStateException.class, () -> failedService.login(USERNAME, PASSWORD)));
		verifyNoInteractions(refreshTokens);
	}

	private UserEntity user(String username, String role, Status status) {
		UserEntity user = mock(UserEntity.class);
		when(user.getId()).thenReturn(USER_ID);
		when(user.getUsername()).thenReturn(username);
		when(user.getRole()).thenReturn(role);
		when(user.getStatus()).thenReturn(status);
		when(user.getPasswordHash()).thenReturn(passwordHash);
		return user;
	}

	private void assertInvalidCredentials(String password) {
		IdentityException error = assertThrows(IdentityException.class, () -> service.login(USERNAME, password));
		assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
		assertEquals(HttpStatus.UNAUTHORIZED, error.getErrorCode().getStatus());
		assertEquals("Invalid credentials", error.getMessage());
	}
}
