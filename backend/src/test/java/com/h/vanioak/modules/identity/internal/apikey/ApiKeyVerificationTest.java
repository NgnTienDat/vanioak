package com.h.vanioak.modules.identity.internal.apikey;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;
import com.h.vanioak.modules.identity.internal.application.ApplicationEntity;
import com.h.vanioak.modules.identity.internal.application.ApplicationRepository;
import com.h.vanioak.modules.identity.internal.application.EnvironmentEntity;
import com.h.vanioak.modules.identity.internal.application.EnvironmentRepository;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ApiKeyVerificationTest {
	private final IngestionCredentialRepository credentials = mock(IngestionCredentialRepository.class);
	private final ApplicationRepository applications = mock(ApplicationRepository.class);
	private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final ValueOperations<String, String> values = mock();
	private final RedisStringCommands strings = mock(RedisStringCommands.class);
	private final ApplicationEntity application = mock(ApplicationEntity.class);
	private final EnvironmentEntity environment = mock(EnvironmentEntity.class);
	private final UUID appId = UUID.randomUUID();
	private final UUID envId = UUID.randomUUID();
	private final String raw = "0123456789abcdef." + "A".repeat(43);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private ApiKeyService service;
	private IngestionCredentialEntity credential;
	private String hash;
	private String stored;
	private Expiration expiration;

	@BeforeEach
	void setup() throws Exception {
		hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		credential = new IngestionCredentialEntity(envId, "0123456789abcdef", hash, UUID.randomUUID(), null);
		ReflectionTestUtils.setField(credential, "id", UUID.randomUUID());
		when(credentials.findByKeyHash(hash)).thenReturn(Optional.of(credential));
		when(environments.findById(envId)).thenReturn(Optional.of(environment));
		when(environment.getApplicationId()).thenReturn(appId);
		when(environment.getName()).thenReturn(ApplicationFacade.EnvironmentName.DEV);
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(applications.findById(appId)).thenReturn(Optional.of(application));
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(application.getName()).thenReturn("example");
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(any())).thenAnswer(call -> stored);
		var connection = mock(RedisConnection.class);
		when(connection.stringCommands()).thenReturn(strings);
		doAnswer(call -> call.<RedisCallback<Boolean>>getArgument(0).doInRedis(connection))
				.when(redis).execute(ArgumentMatchers.<RedisCallback<Boolean>>any());
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenAnswer(call -> {
					assertTrue(new String(call.getArgument(0), StandardCharsets.UTF_8)
							.equals("vanioak:identity:api-key-lookup:" + hash));
					stored = new String(call.getArgument(1), StandardCharsets.UTF_8);
					expiration = call.getArgument(3);
					return true;
				});
		service = new ApiKeyService(credentials, applications, environments, redis, mapper, Duration.ofSeconds(30));
	}

	@Test
	void verifiesHashAndCachesOnlyScopedContextWithFiniteDeadline() {
		long before = Instant.now().toEpochMilli();
		var result = service.verify(raw);
		assertTrue(result.valid());
		assertEquals(appId, result.applicationId());
		assertEquals(envId, result.environmentId());
		assertEquals("example", result.applicationName());
		assertEquals(ApplicationFacade.EnvironmentName.DEV, result.environmentName());
		assertFalse(stored.contains(raw));
		assertFalse(stored.contains(hash));
		assertTrue(expiration.isUnixTimestamp());
		assertFalse(expiration.isPersistent());
		long deadline = expiration.getExpirationTimeInMilliseconds();
		assertEquals(deadline, result.validUntil().toEpochMilli());
		assertTrue(deadline >= before + 30_000 && deadline <= Instant.now().toEpochMilli() + 30_000);
		assertEquals(result, service.verify(raw));
		verify(credentials, times(1)).findByKeyHash(hash);
		verify(strings, times(1)).set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class));
	}

	@Test
	void capsCacheDeadlineAtCredentialExpiry() {
		Instant expiry = Instant.now().plusSeconds(10);
		ReflectionTestUtils.setField(credential, "expiresAt", expiry);
		assertTrue(service.verify(raw).valid());
		assertEquals(expiry.toEpochMilli(), expiration.getExpirationTimeInMilliseconds());
	}

	@Test
	void invalidInputsAndMissingHashDoNotLeakScopeOrCreateCache() {
		for (String invalid : new String[] {null, "", "bad", " " + raw, raw + " "}) {
			var result = service.verify(invalid);
			assertFalse(result.valid());
			assertNull(result.applicationId());
			assertNull(result.environmentId());
		}
		verifyNoInteractions(credentials, redis);
		when(credentials.findByKeyHash(hash)).thenReturn(Optional.empty());
		assertFalse(service.verify(raw).valid());
		assertNull(stored);
	}

	@Test
	void rejectsRevokedExpiredMissingOrDisabledScopes() {
		credential.revoke();
		assertFalse(service.verify(raw).valid());
		ReflectionTestUtils.setField(credential, "status", Status.ACTIVE);
		ReflectionTestUtils.setField(credential, "expiresAt", Instant.EPOCH);
		assertFalse(service.verify(raw).valid());
		ReflectionTestUtils.setField(credential, "expiresAt", null);
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		assertFalse(service.verify(raw).valid());
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		assertFalse(service.verify(raw).valid());
		when(applications.findById(appId)).thenReturn(Optional.empty());
		assertFalse(service.verify(raw).valid());
		when(environments.findById(envId)).thenReturn(Optional.empty());
		assertFalse(service.verify(raw).valid());
		assertNull(stored);
	}

	@Test
	void malformedOrExpiredCachedContextFallsBackToDatabase() {
		stored = "not-json";
		assertTrue(service.verify(raw).valid());
		var tree = mapper.readTree(stored).deepCopy();
		((ObjectNode) tree).put("deadline", Instant.EPOCH.toString());
		stored = mapper.writeValueAsString(tree);
		assertTrue(service.verify(raw).valid());
		verify(credentials, times(2)).findByKeyHash(hash);
	}

	@Test
	void legacyCachedContextWithoutNamesRequiresFreshPostgresVerification() {
		assertTrue(service.verify(raw).valid());
		var old = (ObjectNode) mapper.readTree(stored);
		old.remove("applicationName");
		old.remove("environmentName");
		stored = mapper.writeValueAsString(old);
		assertEquals("example", service.verify(raw).applicationName());
		verify(credentials, times(2)).findByKeyHash(hash);
	}

	@Test
	void redisFailuresUsePostgresButPostgresFailuresPropagate() {
		var redisFailure = new RedisConnectionFailureException("Test failure");
		when(values.get(any())).thenThrow(redisFailure);
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenThrow(redisFailure);
		assertTrue(service.verify(raw).valid());
		var pgFailure = new DataAccessResourceFailureException("Test failure");
		when(credentials.findByKeyHash(hash)).thenThrow(pgFailure);
		assertSame(pgFailure, assertThrows(DataAccessResourceFailureException.class, () -> service.verify(raw)));
	}

	@Test
	void unconfirmedCacheWritesStillReturnOnlyTheAuthoritativeResult() {
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenReturn(null, false);
		assertTrue(service.verify(raw).valid());
		assertTrue(service.verify(raw).valid());
		verify(credentials, times(2)).findByKeyHash(hash);
		assertNull(stored);
	}

	@Test
	void invalidationFailureAfterCommitDoesNotFailManagement() {
		when(credentials.findByIdForUpdate(credential.getId())).thenReturn(Optional.of(credential));
		when(credentials.saveAndFlush(credential)).thenReturn(credential);
		when(redis.delete(any(String.class))).thenThrow(new RedisConnectionFailureException("Test failure"));
		TransactionSynchronizationManager.initSynchronization();
		try {
			service.revoke(credential.getId());
			verify(redis, never()).delete(any(String.class));
			TransactionSynchronizationManager.getSynchronizations().forEach(sync -> assertDoesNotThrow(sync::afterCommit));
			verify(redis).delete(any(String.class));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void rejectsInvalidCacheConfiguration() {
		for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1)})
			assertThrows(IllegalArgumentException.class,
					() -> new ApiKeyService(credentials, applications, environments, redis, mapper, ttl));
	}
}
