package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.AuthFacade.LoginResult;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.auth.AccessTokenBlacklist;
import com.h.vanioak.modules.identity.internal.auth.AuthService;
import com.h.vanioak.modules.identity.internal.auth.JwtService;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, JwtService.class, SecurityConfig.class,
		AccessTokenBlacklist.class, DataRedisAutoConfiguration.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthLogoutIT {

	private static final String PASSWORD = "test-only-logout-password";

	@Autowired
	private AuthFacade auth;
	@Autowired
	private JwtService jwt;
	@Autowired
	private UserRepository users;
	@MockitoSpyBean
	private RefreshTokenRepository tokens;
	@MockitoSpyBean
	private AccessTokenBlacklist blacklist;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	private TransactionTemplate transaction;
	private UUID userId;
	private String username;
	private LoginResult login;
	private final List<String> ownedKeys = new ArrayList<>();

	@BeforeEach
	void setupCommittedSession() {
		transaction = new TransactionTemplate(transactionManager);
		username = "auth-logout-it-" + UUID.randomUUID();
		userId = transaction.execute(status -> users.saveAndFlush(
				new UserEntity(username, encoder.encode(PASSWORD))).getId());
		login = auth.login(username, PASSWORD);
		ownAccessKey(login);
	}

	@AfterEach
	void cleanupOwnedFixtures() {
		reset(tokens, blacklist);
		try {
			if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
		} finally {
			if (userId != null) {
				transaction.executeWithoutResult(status -> {
					entityManager.createQuery("delete from RefreshTokenEntity t where t.userId = :userId")
							.setParameter("userId", userId).executeUpdate();
					users.deleteById(userId);
				});
			}
		}
	}

	@Test
	void committedLogoutRevokesOnlyCurrentFamilyAndBlacklistsAccessUntilExactExpiry() {
		LoginResult current = auth.refresh(login.refreshToken());
		String key = ownAccessKey(current);
		LoginResult otherSession = auth.login(username, PASSWORD);
		ownAccessKey(otherSession);
		UUID currentFamily = family(current);
		UUID otherFamily = family(otherSession);
		doAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			assertTrue(rows().stream().noneMatch(RefreshTokenEntity::isRevoked));
			return invocation.callRealMethod();
		}).when(blacklist).blacklist(any(), any());
		auth.logout(current.refreshToken(), current.accessToken());
		var rows = rows();
		assertEquals(2, rows.stream().filter(row -> row.getFamilyId().equals(currentFamily)).count());
		assertTrue(rows.stream().filter(row -> row.getFamilyId().equals(currentFamily)).allMatch(RefreshTokenEntity::isRevoked));
		assertTrue(rows.stream().filter(row -> row.getFamilyId().equals(otherFamily)).noneMatch(RefreshTokenEntity::isRevoked));
		assertFalse(blacklist.isBlacklisted(UUID.fromString(jwt.parseAccessToken(otherSession.accessToken()).getId())));
		assertBlacklist(current, key);
		long deadline = deadline(key);
		unauthorized(() -> auth.logout(current.refreshToken(), current.accessToken()));
		assertEquals(3, rows().size());
		assertEquals(deadline, deadline(key));
	}

	@Test
	void logoutWithoutAccessCommitsOnlyFamilyRevocation() {
		auth.logout(login.refreshToken(), null);
		assertTrue(rows().stream().allMatch(RefreshTokenEntity::isRevoked));
		assertFalse(blacklist.isBlacklisted(UUID.fromString(jwt.parseAccessToken(login.accessToken()).getId())));
		unauthorized(() -> auth.logout(login.refreshToken(), null));
	}

	@Test
	void redisFailureRollsBackPostgresAndEligibleCredentialCanRetryAfterRecovery() {
		var failure = new RedisConnectionFailureException("Test injected Redis failure");
		var completion = new AtomicInteger(-1);
		doAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					completion.set(status);
				}
			});
			throw failure;
		}).when(blacklist).blacklist(any(), any());
		assertSame(failure, assertThrows(RedisConnectionFailureException.class,
				() -> auth.logout(login.refreshToken(), login.accessToken())));
		assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, completion.get());
		assertTrue(rows().stream().noneMatch(RefreshTokenEntity::isRevoked));
		assertFalse(blacklist.isBlacklisted(UUID.fromString(jwt.parseAccessToken(login.accessToken()).getId())));
		reset(blacklist);
		auth.logout(login.refreshToken(), login.accessToken());
		assertTrue(rows().stream().allMatch(RefreshTokenEntity::isRevoked));
		assertBlacklist(login, ownedKeys.getFirst());
	}

	@Test
	void postgresFailureAfterRealRedisWriteKeepsBlacklistAndRollsBackFamilyRevocation() {
		var failure = new DataAccessResourceFailureException("Test injected failure after database update");
		var repositoryAnswer = mockingDetails(tokens).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			repositoryAnswer.answer(invocation);
			throw failure;
		}).when(tokens).revokeFamily(family(login));
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> auth.logout(login.refreshToken(), login.accessToken())));
		assertTrue(rows().stream().noneMatch(RefreshTokenEntity::isRevoked));
		assertBlacklist(login, ownedKeys.getFirst());
		long originalDeadline = deadline(ownedKeys.getFirst());
		reset(tokens);
		auth.logout(login.refreshToken(), login.accessToken());
		assertTrue(rows().stream().allMatch(RefreshTokenEntity::isRevoked));
		assertEquals(originalDeadline, deadline(ownedKeys.getFirst()));
		unauthorized(() -> auth.logout(login.refreshToken(), login.accessToken()));
	}

	private UUID family(LoginResult result) {
		return UUID.fromString(jwt.parseRefreshToken(result.refreshToken()).get("family_id", String.class));
	}

	private String ownAccessKey(LoginResult result) {
		String key = "vanioak:identity:access-blacklist:" + jwt.parseAccessToken(result.accessToken()).getId();
		ownedKeys.add(key);
		return key;
	}

	private void assertBlacklist(LoginResult result, String key) {
		var access = jwt.parseAccessToken(result.accessToken());
		assertTrue(blacklist.isBlacklisted(UUID.fromString(access.getId())));
		assertEquals("1", redis.opsForValue().get(key));
		assertEquals(access.getExpiration().toInstant().toEpochMilli(), deadline(key));
		long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
		assertTrue(ttl > 0 && ttl <= 900_000);
	}

	private long deadline(String key) {
		return redis.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
	}

	private List<RefreshTokenEntity> rows() {
		return transaction.execute(status -> entityManager.createQuery(
				"select t from RefreshTokenEntity t where t.userId = :userId", RefreshTokenEntity.class)
				.setParameter("userId", userId).getResultList());
	}

	private void unauthorized(org.junit.jupiter.api.function.Executable operation) {
		var error = assertThrows(IdentityException.class, operation);
		assertEquals(ErrorCode.INVALID_CREDENTIALS, error.getErrorCode());
		assertEquals("Invalid credentials", error.getMessage());
	}
}
