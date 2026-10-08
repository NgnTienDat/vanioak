package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import com.h.vanioak.modules.identity.api.AuthFacade.AuthenticatedUser;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@SpringBootTest(classes = JwtService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessTokenAuthenticationTest {

	@Autowired
	private JwtService jwt;
	@Value("${security.jwt.access-secret}")
	private String accessSecret;
	private final UUID userId = UUID.randomUUID();
	private UserRepository users;
	private AccessTokenBlacklist blacklist;
	private AuthService auth;

	@BeforeEach
	void setup() {
		users = mock(UserRepository.class);
		blacklist = mock(AccessTokenBlacklist.class);
		auth = new AuthService(users, mock(PasswordEncoder.class), jwt, mock(RefreshTokenRepository.class),
				mock(PlatformTransactionManager.class), blacklist);
	}

	@Test
	void currentDatabaseRoleAndStatusAreLoadedAfterBlacklistForEachRequest() {
		String token = jwt.generateAccessToken(userId);
		UUID jti = UUID.fromString(jwt.parseAccessToken(token).getId());
		UserEntity user = mock(UserEntity.class);
		when(user.getId()).thenReturn(userId);
		when(user.getStatus()).thenReturn(Status.ACTIVE, Status.ACTIVE, Status.DISABLED);
		when(user.getRole()).thenReturn("ADMIN", "ENGINEER");
		when(users.findById(userId)).thenReturn(Optional.of(user));
		assertEquals(new AuthenticatedUser(userId, "ADMIN"), auth.authenticateAccessToken(token));
		assertEquals(new AuthenticatedUser(userId, "ENGINEER"), auth.authenticateAccessToken(token));
		assertEquals(ErrorCode.ACCESS_DENIED,
				assertThrows(IdentityException.class, () -> auth.authenticateAccessToken(token)).getErrorCode());
		var order = inOrder(blacklist, users);
		for (int i = 0; i < 3; i++) {
			order.verify(blacklist).isBlacklisted(jti);
			order.verify(users).findById(userId);
		}
	}

	@Test
	void invalidExpiredAndRefreshCredentialsDoNotReachStorage() {
		Instant now = Instant.now();
		String expired = Jwts.builder().subject(userId.toString()).id(UUID.randomUUID().toString())
				.issuedAt(Date.from(now.minusSeconds(120))).expiration(Date.from(now.minusSeconds(60)))
				.claim("token_use", "access")
				.signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(accessSecret)), Jwts.SIG.HS256).compact();
		for (String token : new String[] {null, "", "invalid", expired,
				jwt.generateRefreshToken(userId, UUID.randomUUID(), UUID.randomUUID())}) {
			assertEquals(ErrorCode.INVALID_CREDENTIALS,
					assertThrows(IdentityException.class, () -> auth.authenticateAccessToken(token)).getErrorCode());
		}
		verifyNoInteractions(blacklist, users);
	}

	@Test
	void blacklistedTokenIsRejectedBeforeUserLookup() {
		when(blacklist.isBlacklisted(any())).thenReturn(true);
		assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(IdentityException.class,
				() -> auth.authenticateAccessToken(jwt.generateAccessToken(userId))).getErrorCode());
		verifyNoInteractions(users);
	}

	@Test
	void missingUserIsInvalidCredentials() {
		assertEquals(ErrorCode.INVALID_CREDENTIALS, assertThrows(IdentityException.class,
				() -> auth.authenticateAccessToken(jwt.generateAccessToken(userId))).getErrorCode());
	}

	@Test
	void infrastructureFailuresPropagate() {
		String token = jwt.generateAccessToken(userId);
		var redisFailure = new IllegalStateException("test infrastructure failure");
		when(blacklist.isBlacklisted(any())).thenThrow(redisFailure);
		assertSame(redisFailure, assertThrows(IllegalStateException.class, () -> auth.authenticateAccessToken(token)));
		verifyNoInteractions(users);
		doReturn(false).when(blacklist).isBlacklisted(any());
		var databaseFailure = new IllegalStateException("test database failure");
		when(users.findById(userId)).thenThrow(databaseFailure);
		assertSame(databaseFailure, assertThrows(IllegalStateException.class, () -> auth.authenticateAccessToken(token)));
	}
}
