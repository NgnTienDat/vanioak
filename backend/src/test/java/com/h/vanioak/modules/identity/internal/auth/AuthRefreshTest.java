package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@SpringBootTest(classes = JwtService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthRefreshTest {

	@Autowired
	private JwtService jwt;
	@Value("${security.jwt.refresh-secret}")
	private String refreshSecret;
	private final UUID userId = UUID.randomUUID();
	private final UUID tokenId = UUID.randomUUID();
	private final UUID familyId = UUID.randomUUID();
	private UserRepository users;
	private RefreshTokenRepository tokens;
	private PlatformTransactionManager transactions;
	private AuthService service;
	private String token;
	private RefreshTokenEntity current;
	private UserEntity user;

	@BeforeEach
	void setup() {
		users = mock(UserRepository.class);
		tokens = mock(RefreshTokenRepository.class);
		transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new AuthService(users, mock(PasswordEncoder.class), jwt, tokens, transactions,
				mock(AccessTokenBlacklist.class));
		token = jwt.generateRefreshToken(userId, tokenId, familyId);
		current = new RefreshTokenEntity(tokenId, familyId, null, userId,
				jwt.parseRefreshToken(token).getExpiration().toInstant());
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.of(current));
		when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.of(current));
		user = mock(UserEntity.class);
		when(user.getId()).thenReturn(userId);
		when(user.getUsername()).thenReturn("refresh-test-user");
		when(user.getRole()).thenReturn("ENGINEER");
		when(user.getStatus()).thenReturn(Status.ACTIVE);
		when(users.findById(userId)).thenReturn(Optional.of(user));
	}

	@Test
	void rotatesWithinFamilyWithNewIdAndMatchingJwtMetadata() {
		var result = service.refresh(token);
		var claims = jwt.parseRefreshToken(result.refreshToken());
		var saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
		verify(tokens).saveAndFlush(saved.capture());
		var child = saved.getValue();
		assertTrue(current.isUsed());
		assertFalse(current.isRevoked());
		assertNotEquals(tokenId, child.getTokenId());
		assertEquals(UUID.fromString(claims.getId()), child.getTokenId());
		assertEquals(familyId, child.getFamilyId());
		assertEquals(familyId.toString(), claims.get("family_id"));
		assertEquals(tokenId, child.getParentTokenId());
		assertEquals(userId, child.getUserId());
		assertEquals(claims.getExpiration().toInstant(), child.getExpiresAt());
		assertFalse(child.isUsed());
		assertFalse(child.isRevoked());
		assertEquals(userId.toString(), jwt.parseAccessToken(result.accessToken()).getSubject());
		assertEquals("Bearer", result.tokenType());
		assertEquals(900, result.expiresIn());
		assertEquals(userId, result.user().id());
		var order = inOrder(tokens);
		order.verify(tokens).findFamilyRootForUpdate(familyId);
		order.verify(tokens).findByTokenIdForUpdate(tokenId);
		order.verify(tokens).saveAndFlush(any());
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void invalidCryptographicCredentialsFailBeforeDatabaseTransaction() {
		Instant now = Instant.now();
		var key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(refreshSecret));
		String expired = Jwts.builder().subject(userId.toString()).id(tokenId.toString())
				.claim("family_id", familyId.toString()).claim("token_use", "refresh")
				.issuedAt(Date.from(now.minusSeconds(120))).expiration(Date.from(now.minusSeconds(60)))
				.signWith(key, Jwts.SIG.HS256).compact();
		String invalidUuid = Jwts.builder().subject("invalid-uuid").id(tokenId.toString())
				.claim("family_id", familyId.toString()).claim("token_use", "refresh")
				.issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
				.signWith(key, Jwts.SIG.HS256).compact();
		for (String input : new String[] { null, "", "malformed", expired, invalidUuid, jwt.generateAccessToken(userId) }) {
			assertUnauthorized(() -> service.refresh(input));
		}
		verifyNoInteractions(transactions);
		verify(tokens, never()).findFamilyRootForUpdate(any());
		verify(tokens, never()).findByTokenIdForUpdate(any());
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void mismatchedMetadataDoesNotRevokeEvenIfMarkedUsed() {
		for (var mismatched : new RefreshTokenEntity[] {
				new RefreshTokenEntity(tokenId, familyId, null, UUID.randomUUID(), current.getExpiresAt()),
				new RefreshTokenEntity(tokenId, UUID.randomUUID(), null, userId, current.getExpiresAt()),
				new RefreshTokenEntity(tokenId, familyId, null, userId, current.getExpiresAt().plusSeconds(1)),
				new RefreshTokenEntity(tokenId, familyId, null, userId, Instant.now().minusSeconds(1)) }) {
			mismatched.setUsed(true);
			when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.of(mismatched));
			assertUnauthorized(() -> service.refresh(token));
		}
		verify(tokens, never()).revokeFamily(any());
		verify(tokens, never()).saveAndFlush(any());
	}

	@Test
	void missingRootOrCurrentRowIsSafeUnauthorized() {
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.empty());
		assertUnauthorized(() -> service.refresh(token));
		when(tokens.findFamilyRootForUpdate(familyId)).thenReturn(Optional.of(current));
		when(tokens.findByTokenIdForUpdate(tokenId)).thenReturn(Optional.empty());
		assertUnauthorized(() -> service.refresh(token));
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void usedOrRevokedTokensRevokeFamilyAndReturnSameSafeError() {
		current.setUsed(true);
		assertUnauthorized(() -> service.refresh(token));
		current.setUsed(false);
		current.setRevoked(true);
		assertUnauthorized(() -> service.refresh(token));
		verify(tokens, times(2)).revokeFamily(familyId);
		verify(users, never()).findById(any());
		verify(tokens, never()).saveAndFlush(any());
	}

	@Test
	void missingOrDisabledUserIsUnauthorizedBeforeConsumingToken() {
		when(users.findById(userId)).thenReturn(Optional.empty());
		assertUnauthorized(() -> service.refresh(token));
		when(users.findById(userId)).thenReturn(Optional.of(user));
		when(user.getStatus()).thenReturn(Status.DISABLED);
		assertUnauthorized(() -> service.refresh(token));
		assertFalse(current.isUsed());
		verify(tokens, never()).saveAndFlush(any());
		verify(tokens, never()).revokeFamily(any());
	}

	@Test
	void repositoryAndPersistenceFailuresPropagate() {
		var failure = new DataAccessResourceFailureException("Test database failure");
		doThrow(failure).when(tokens).findByTokenIdForUpdate(tokenId);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.refresh(token)));
		doReturn(Optional.of(current)).when(tokens).findByTokenIdForUpdate(tokenId);
		doThrow(failure).when(users).findById(userId);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.refresh(token)));
		doReturn(Optional.of(user)).when(users).findById(userId);
		doThrow(failure).when(tokens).saveAndFlush(any());
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.refresh(token)));
		current.setUsed(true);
		doThrow(failure).when(tokens).revokeFamily(familyId);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.refresh(token)));
	}

	@Test
	void ambiguousRootsRemainInternalIntegrityFailure() {
		var failure = new IncorrectResultSizeDataAccessException(1, 2);
		doThrow(failure).when(tokens).findFamilyRootForUpdate(familyId);
		assertSame(failure, assertThrows(IncorrectResultSizeDataAccessException.class, () -> service.refresh(token)));
		verify(tokens, never()).revokeFamily(any());
	}

	private void assertUnauthorized(org.junit.jupiter.api.function.Executable operation) {
		var error = assertThrows(IdentityException.class, operation);
		assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
		assertEquals("Invalid credentials", error.getMessage());
	}
}
