package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.AuthFacade.LoginResult;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.auth.AuthService;
import com.h.vanioak.modules.identity.internal.auth.AccessTokenBlacklist;
import com.h.vanioak.modules.identity.internal.auth.JwtService;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, JwtService.class, SecurityConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthRefreshIT {

	@MockitoBean
	private AccessTokenBlacklist accessTokenBlacklist;

	@Autowired
	private AuthFacade auth;
	@MockitoSpyBean
	private JwtService jwt;
	@MockitoSpyBean
	private UserRepository users;
	@MockitoSpyBean
	private RefreshTokenRepository tokens;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	private TransactionTemplate transaction;
	private UUID userId;
	private LoginResult login;

	@BeforeEach
	void createCommittedSession() {
		transaction = new TransactionTemplate(transactionManager);
		String username = "auth-refresh-it-" + UUID.randomUUID();
		String password = "test-only-refresh-password";
		userId = transaction.execute(status -> users.saveAndFlush(
				new UserEntity(username, encoder.encode(password))).getId());
		login = auth.login(username, password);
	}

	@AfterEach
	void removeOnlyOwnedFixtures() {
		reset(jwt, users, tokens);
		if (userId != null) {
			transaction.executeWithoutResult(status -> {
				entityManager.createQuery("delete from RefreshTokenEntity t where t.userId = :userId")
						.setParameter("userId", userId).executeUpdate();
				users.deleteById(userId);
			});
		}
	}

	@Test
	void committedRotationAndReplayPersistExpectedMetadata() {
		var root = metadata(login);
		assertFalse(root.isUsed());
		assertFalse(root.isRevoked());
		assertNull(root.getParentTokenId());
		var rotated = auth.refresh(login.refreshToken());
		var child = metadata(rotated);
		assertTrue(metadata(login).isUsed());
		assertNotEquals(root.getTokenId(), child.getTokenId());
		assertEquals(root.getTokenId(), child.getParentTokenId());
		assertEquals(root.getFamilyId(), child.getFamilyId());
		assertEquals(userId, child.getUserId());
		assertEquals(jwt.parseRefreshToken(rotated.refreshToken()).getExpiration().toInstant(), child.getExpiresAt());
		assertFalse(child.isUsed());
		assertFalse(child.isRevoked());
		var grandchild = auth.refresh(rotated.refreshToken());
		assertEquals(child.getTokenId(), metadata(grandchild).getParentTokenId());
		assertEquals(root.getFamilyId(), metadata(grandchild).getFamilyId());
		// A surrounding caller rollback must not undo the independently committed replay revocation.
		transaction.executeWithoutResult(status -> {
			assertUnauthorized(login.refreshToken());
			status.setRollbackOnly();
		});
		assertTrue(rows().stream().allMatch(RefreshTokenEntity::isRevoked));
		assertUnauthorized(rotated.refreshToken());
		assertEquals(3, rows().size());
		// Family revocation does not change cryptographic validity of issued access JWTs.
		assertEquals(userId.toString(), jwt.parseAccessToken(rotated.accessToken()).getSubject());
	}

	@Test
	void mismatchedSignedMetadataDoesNotRevokeFamily() {
		var root = metadata(login);
		String mismatched = jwt.generateRefreshToken(UUID.randomUUID(), root.getTokenId(), root.getFamilyId());
		assertUnauthorized(mismatched);
		assertFalse(metadata(login).isUsed());
		assertFalse(metadata(login).isRevoked());
	}

	@Test
	void missingOrDisabledUserDoesNotConsumeToken() {
		doReturn(Optional.empty()).when(users).findById(userId);
		assertUnauthorized(login.refreshToken());
		assertFalse(metadata(login).isUsed());
		reset(users);
		transaction.executeWithoutResult(status -> users.findById(userId).orElseThrow().update(null, null, Status.DISABLED));
		assertUnauthorized(login.refreshToken());
		assertFalse(metadata(login).isUsed());
		assertEquals(1, rows().size());
	}

	@Test
	void issuanceFailureRollsBackConsumedState() {
		var failure = new IllegalStateException("Test issuance failure");
		doThrow(failure).when(jwt).generateAccessToken(userId);
		assertSame(failure, assertThrows(IllegalStateException.class, () -> auth.refresh(login.refreshToken())));
		assertFalse(metadata(login).isUsed());
		assertEquals(1, rows().size());
	}

	@Test
	void actualUniqueParentFailureRollsBackConsumedState() {
		var root = metadata(login);
		transaction.executeWithoutResult(status -> tokens.saveAndFlush(new RefreshTokenEntity(
				UUID.randomUUID(), root.getFamilyId(), root.getTokenId(), userId, root.getExpiresAt())));
		assertThrows(DataIntegrityViolationException.class, () -> auth.refresh(login.refreshToken()));
		assertFalse(metadata(login).isUsed());
		assertFalse(metadata(login).isRevoked());
		assertEquals(2, rows().size());
	}

	@Test
	void simultaneousRefreshesConsumeOnceAndReplayRevokesChild() throws Exception {
		var results = concurrentRefresh(login, login);
		assertEquals(1, results.stream().filter(result -> result != null).count());
		var rows = rows();
		assertEquals(2, rows.size());
		assertTrue(metadata(login).isUsed());
		assertEquals(1, rows.stream().filter(row -> row.getParentTokenId() != null).count());
		assertTrue(rows.stream().allMatch(RefreshTokenEntity::isRevoked));
	}

	@Test
	void ancestorReplayAndDescendantRotationLeaveNoActiveDescendant() throws Exception {
		var descendant = auth.refresh(login.refreshToken());
		var results = concurrentRefresh(login, descendant);
		assertNull(results.get(0));
		var rows = rows();
		assertEquals(results.get(1) == null ? 2 : 3, rows.size());
		assertTrue(rows.stream().allMatch(RefreshTokenEntity::isRevoked));
		assertTrue(metadata(login).isUsed());
	}

	private List<LoginResult> concurrentRefresh(LoginResult first, LoginResult second) throws Exception {
		UUID familyId = metadata(first).getFamilyId();
		var attemptedRootLock = new CountDownLatch(2);
		var repositoryAnswer = mockingDetails(tokens).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			if (Thread.currentThread().getName().startsWith("refresh-it-worker")) attemptedRootLock.countDown();
			return repositoryAnswer.answer(invocation);
		}).when(tokens).findFamilyRootForUpdate(familyId);
		ExecutorService executor = Executors.newFixedThreadPool(2, task -> new Thread(task, "refresh-it-worker"));
		List<Future<LoginResult>> futures = new ArrayList<>();
		try {
			transaction.executeWithoutResult(status -> {
				tokens.findFamilyRootForUpdate(familyId).orElseThrow();
				futures.add(executor.submit(() -> refreshOrUnauthorized(first.refreshToken())));
				futures.add(executor.submit(() -> refreshOrUnauthorized(second.refreshToken())));
				try {
					assertTrue(attemptedRootLock.await(10, TimeUnit.SECONDS));
					// Both workers reached the real locking query and cannot finish while this root is locked.
					for (var future : futures) {
						assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
					}
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Test lock coordination interrupted", error);
				}
			});
			return Arrays.asList(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
		} finally {
			for (var future : futures) future.cancel(true);
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	private LoginResult refreshOrUnauthorized(String token) {
		try {
			return auth.refresh(token);
		} catch (IdentityException error) {
			assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
			assertEquals("Invalid credentials", error.getMessage());
			return null;
		}
	}

	private void assertUnauthorized(String token) {
		var error = assertThrows(IdentityException.class, () -> auth.refresh(token));
		assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
		assertEquals("Invalid credentials", error.getMessage());
	}

	private RefreshTokenEntity metadata(LoginResult result) {
		UUID tokenId = UUID.fromString(jwt.parseRefreshToken(result.refreshToken()).getId());
		return transaction.execute(status -> tokens.findById(tokenId).orElseThrow());
	}

	private List<RefreshTokenEntity> rows() {
		return transaction.execute(status -> entityManager.createQuery(
				"select t from RefreshTokenEntity t where t.userId = :userId", RefreshTokenEntity.class)
				.setParameter("userId", userId).getResultList());
	}
}
