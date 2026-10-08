package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@SpringBootTest(classes = JwtService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthLogoutTest {

	@Autowired
	private JwtService jwt;
	@Value("${security.jwt.access-secret}")
	private String accessSecret;
	@Value("${security.jwt.refresh-secret}")
	private String refreshSecret;
	private final UUID userId = UUID.randomUUID();
	private final UUID tokenId = UUID.randomUUID();
	private final UUID familyId = UUID.randomUUID();
	private RefreshTokenRepository tokens;
	private AccessTokenBlacklist blacklist;
	private AuthService service;
	private String refresh;
	private String access;
	private RefreshTokenEntity current;

	@BeforeEach
	void setup() {
		tokens = mock(RefreshTokenRepository.class);
		blacklist = mock(AccessTokenBlacklist.class);
		var transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new AuthService(mock(UserRepository.class), mock(PasswordEncoder.class), jwt, tokens,
				transactions, blacklist);
		refresh = jwt.generateRefreshToken(userId, tokenId, familyId);
		access = jwt.generateAccessToken(userId);
		current = new RefreshTokenEntity(tokenId, familyId, null, userId,
				jwt.parseRefreshToken(refresh).getExpiration().toInstant());
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.of(current));
		when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.of(current));
	}

	@Test
	void locksRootThenCurrentAndBlacklistsExactAccessIdentityBeforeFamilyRevocation() {
		var claims = jwt.parseAccessToken(access);
		service.logout(refresh, access);
		var order = inOrder(tokens, blacklist);
		order.verify(tokens).findFamilyRootForUpdate(familyId);
		order.verify(tokens).findByTokenIdForUpdate(tokenId);
		order.verify(blacklist).blacklist(UUID.fromString(claims.getId()), claims.getExpiration().toInstant());
		order.verify(tokens).revokeFamily(familyId);
		order.verifyNoMoreInteractions();
	}

	@Test
	void absentInvalidExpiredAndWrongTypeOptionalAccessDoNotBlockLogout() {
		for (String optional : new String[] { null, "", "malformed", expired("access", accessSecret), refresh }) {
			service.logout(refresh, optional);
		}
		verify(tokens, times(5)).revokeFamily(familyId);
		verifyNoInteractions(blacklist);
	}

	@Test
	void missingInvalidExpiredAndWrongTypeRefreshAreSafeUnauthorized() {
		for (String input : new String[] { null, "", "malformed", expired("refresh", refreshSecret), access }) {
			unauthorized(() -> service.logout(input, access));
		}
		verifyNoInteractions(tokens, blacklist);
	}

	@Test
	void verifiedAccessForDifferentUserCannotMutateAnySession() {
		String otherAccess = jwt.generateAccessToken(UUID.randomUUID());
		unauthorized(() -> service.logout(refresh, otherAccess));
		verifyNoInteractions(tokens, blacklist);
	}

	@Test
	void missingRootOrCurrentMetadataIsUnauthorizedWithoutMutation() {
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.empty());
		unauthorized(() -> service.logout(refresh, access));
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.of(current));
		when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.empty());
		unauthorized(() -> service.logout(refresh, access));
		verifyNoInteractions(blacklist);
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void mismatchedUserFamilyOrExpiryCannotRevokeUnrelatedMetadata() {
		for (var row : new RefreshTokenEntity[] {
				new RefreshTokenEntity(tokenId, familyId, null, UUID.randomUUID(), current.getExpiresAt()),
				new RefreshTokenEntity(tokenId, UUID.randomUUID(), null, userId, current.getExpiresAt()),
				new RefreshTokenEntity(tokenId, familyId, null, userId, current.getExpiresAt().plusSeconds(1)),
				new RefreshTokenEntity(tokenId, familyId, null, userId, Instant.EPOCH) }) {
			when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.of(row));
			unauthorized(() -> service.logout(refresh, access));
		}
		verifyNoInteractions(blacklist);
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void usedOrRevokedCurrentCredentialsRemainUnauthorized() {
		current.setUsed(true);
		unauthorized(() -> service.logout(refresh, access));
		current.setUsed(false);
		current.setRevoked(true);
		unauthorized(() -> service.logout(refresh, access));
		verifyNoInteractions(blacklist);
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void databaseLookupAndRevocationFailuresPropagate() {
		var failure = new DataAccessResourceFailureException("Test database failure");
		when(tokens.findByTokenIdForUpdate(tokenId)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.logout(refresh, access)));
		verifyNoInteractions(blacklist);
		doReturn(Optional.of(current)).when(tokens).findByTokenIdForUpdate(tokenId);
		when(tokens.revokeFamily(familyId)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.logout(refresh, access)));
		var claims = jwt.parseAccessToken(access);
		verify(blacklist).blacklist(UUID.fromString(claims.getId()), claims.getExpiration().toInstant());
	}

	@Test
	void redisFailurePropagatesAndStillEligibleCredentialCanRetry() {
		var claims = jwt.parseAccessToken(access);
		UUID jti = UUID.fromString(claims.getId());
		Instant expiry = claims.getExpiration().toInstant();
		var failure = new RedisConnectionFailureException("Test Redis failure");
		doThrow(failure).when(blacklist).blacklist(jti, expiry);
		assertSame(failure, assertThrows(RedisConnectionFailureException.class, () -> service.logout(refresh, access)));
		verify(tokens, never()).revokeFamily(any());
		doNothing().when(blacklist).blacklist(jti, expiry);
		service.logout(refresh, access);
		verify(blacklist, times(2)).blacklist(jti, expiry);
		verify(tokens).revokeFamily(familyId);
	}

	private String expired(String tokenUse, String secret) {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).id(tokenId.toString())
				.claim("token_use", tokenUse).claim("family_id", familyId.toString())
				.issuedAt(Date.from(now.minusSeconds(120))).expiration(Date.from(now.minusSeconds(60)))
				.signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)), Jwts.SIG.HS256).compact();
	}

	private void unauthorized(Executable operation) {
		var error = assertThrows(IdentityException.class, operation);
		assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
		assertEquals("Invalid credentials", error.getMessage());
	}
}
