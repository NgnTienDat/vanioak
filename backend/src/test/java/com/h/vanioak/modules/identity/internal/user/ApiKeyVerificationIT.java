package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.internal.apikey.ApiKeyService;
import com.h.vanioak.modules.identity.internal.apikey.IngestionCredentialRepository;
import com.h.vanioak.modules.identity.internal.application.ApplicationService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ApiKeyService.class, ApplicationService.class, SecurityConfig.class,
		DataRedisAutoConfiguration.class, ApiKeyVerificationIT.JsonConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyVerificationIT {
	@TestConfiguration
	static class JsonConfiguration {
		@Bean
		ObjectMapper mapper() { return JsonMapper.builder().build(); }
	}
	@Autowired
	private ApiKeyFacade keys;
	@Autowired
	private ApplicationFacade applications;
	@Autowired
	private UserRepository users;
	@Autowired
	private IngestionCredentialRepository credentials;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoSpyBean
	private StringRedisTemplate redis;
	private final List<String> ownedKeys = new ArrayList<>();
	private UUID actor;
	private UUID appId;
	private List<UUID> environmentIds = List.of();

	@BeforeEach
	void fixtures() {
		actor = transaction().execute(status -> users.saveAndFlush(UserEntity.initialAdmin(
				"verify-it-" + UUID.randomUUID(), encoder.encode("test-only-password"))).getId());
		var app = applications.create(new ApplicationFacade.CreateApplication("verify-it-" + UUID.randomUUID(), null));
		appId = app.id();
		environmentIds = app.environments().stream().map(ApplicationFacade.EnvironmentView::id).toList();
	}

	@Test
	void realLookupCachesScopedContextUntilAbsoluteExpiryWithoutExtendingHits() throws Exception {
		Instant expiry = Instant.now().plusSeconds(20).truncatedTo(ChronoUnit.SECONDS);
		var issued = issue(expiry);
		String cacheKey = own(issued);
		var result = keys.verify(issued.apiKey());
		assertTrue(result.valid());
		assertEquals(appId, result.applicationId());
		assertEquals(environmentIds.getFirst(), result.environmentId());
		String value = redis.opsForValue().get(cacheKey);
		assertNotNull(value);
		assertFalse(value.contains(issued.apiKey()));
		assertFalse(value.contains(cacheKey.substring(cacheKey.lastIndexOf(':') + 1)));
		assertEquals(expiry.toEpochMilli(), deadline(cacheKey));
		long ttl = redis.getExpire(cacheKey, TimeUnit.MILLISECONDS);
		assertTrue(ttl > 0 && ttl <= 20_000);
		assertEquals(result, keys.verify(issued.apiKey()));
		assertEquals(expiry.toEpochMilli(), deadline(cacheKey));

		transaction().executeWithoutResult(status -> {
			entityManager.createQuery("update EnvironmentEntity e set e.status = :status where e.id = :id")
					.setParameter("status", ApplicationFacade.Status.DISABLED)
					.setParameter("id", environmentIds.getFirst()).executeUpdate();
			entityManager.flush();
			entityManager.clear();
		});
		redis.delete(cacheKey);
		assertFalse(keys.verify(issued.apiKey()).valid());
		transaction().executeWithoutResult(status -> entityManager.createQuery(
				"update EnvironmentEntity e set e.status = :status where e.id = :id")
				.setParameter("status", ApplicationFacade.Status.ACTIVE).setParameter("id", environmentIds.getFirst()).executeUpdate());
		assertTrue(keys.verify(issued.apiKey()).valid());
		applications.update(appId, new ApplicationFacade.UpdateApplication(null, null, ApplicationFacade.Status.DISABLED));
		redis.delete(cacheKey);
		assertFalse(keys.verify(issued.apiKey()).valid());
	}

	@Test
	void committedRotationAndRevokeInvalidateButRollbackPreservesOldCache() throws Exception {
		var original = issue(null);
		String cacheKey = own(original);
		assertTrue(keys.verify(original.apiKey()).valid());
		transaction().executeWithoutResult(status -> {
			keys.revoke(original.credential().id());
			assertTrue(Boolean.TRUE.equals(redis.hasKey(cacheKey)));
			status.setRollbackOnly();
		});
		assertEquals(ApiKeyFacade.Status.ACTIVE, transaction().execute(
				status -> credentials.findById(original.credential().id()).orElseThrow().getStatus()));
		assertTrue(keys.verify(original.apiKey()).valid());
		var replacement = keys.rotate(original.credential().id(), actor);
		String replacementKey = own(replacement);
		assertFalse(Boolean.TRUE.equals(redis.hasKey(cacheKey)));
		assertFalse(keys.verify(original.apiKey()).valid());
		assertTrue(keys.verify(replacement.apiKey()).valid());
		keys.revoke(replacement.credential().id());
		assertFalse(Boolean.TRUE.equals(redis.hasKey(replacementKey)));
		assertFalse(keys.verify(replacement.apiKey()).valid());
	}

	@Test
	void injectedRedisFailuresAllowPgVerificationAndCommittedRevocation() throws Exception {
		var issued = issue(null);
		String cacheKey = own(issued);
		doThrow(new RedisConnectionFailureException("Injected lookup failure")).when(redis).opsForValue();
		assertTrue(keys.verify(issued.apiKey()).valid());
		reset(redis);
		assertTrue(Boolean.TRUE.equals(redis.hasKey(cacheKey)));
		Long originalDeadline = deadline(cacheKey);
		doThrow(new RedisConnectionFailureException("Injected invalidation failure")).when(redis).delete(anyString());
		assertEquals(ApiKeyFacade.Status.REVOKED, keys.revoke(issued.credential().id()).status());
		assertEquals(ApiKeyFacade.Status.REVOKED, transaction().execute(
				status -> credentials.findById(issued.credential().id()).orElseThrow().getStatus()));
		reset(redis);
		assertEquals(originalDeadline, deadline(cacheKey));
		long ttl = redis.getExpire(cacheKey, TimeUnit.MILLISECONDS);
		assertTrue(ttl > 0 && ttl <= 30_000);
		redis.delete(cacheKey);
		assertFalse(keys.verify(issued.apiKey()).valid());
	}

	private ApiKeyFacade.IssuedKey issue(Instant expiry) {
		return keys.create(appId, environmentIds.getFirst(), actor, expiry);
	}

	private String own(ApiKeyFacade.IssuedKey issued) throws Exception {
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(issued.apiKey().getBytes(StandardCharsets.UTF_8)));
		String key = "vanioak:identity:api-key-lookup:" + hash;
		ownedKeys.add(key);
		return key;
	}

	private Long deadline(String key) {
		return redis.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
	}

	private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }

	@AfterEach
	void cleanup() {
		reset(redis);
		try {
			if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
		} finally {
			transaction().executeWithoutResult(status -> {
				if (!environmentIds.isEmpty()) {
					entityManager.createQuery("delete from IngestionCredentialEntity c where c.environmentId in :ids")
							.setParameter("ids", environmentIds).executeUpdate();
					entityManager.createQuery("delete from EnvironmentEntity e where e.id in :ids")
							.setParameter("ids", environmentIds).executeUpdate();
				}
				if (appId != null) entityManager.createQuery("delete from ApplicationEntity a where a.id = :id")
						.setParameter("id", appId).executeUpdate();
				if (actor != null) users.deleteById(actor);
			});
		}
	}
}
