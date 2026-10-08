package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

class AccessTokenBlacklistTest {

	private final UUID jti = UUID.randomUUID();
	private final String key = "vanioak:identity:access-blacklist:" + jti;
	private StringRedisTemplate redis;
	private RedisStringCommands strings;
	private AccessTokenBlacklist blacklist;

	@BeforeEach
	void setup() {
		redis = mock(StringRedisTemplate.class);
		var connection = mock(RedisConnection.class);
		strings = mock(RedisStringCommands.class);
		when(connection.stringCommands()).thenReturn(strings);
		doAnswer(invocation -> invocation.<RedisCallback<Boolean>>getArgument(0).doInRedis(connection))
				.when(redis).execute(ArgumentMatchers.<RedisCallback<Boolean>>any());
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenReturn(true);
		blacklist = new AccessTokenBlacklist(redis);
	}

	@Test
	void writesOnlyJtiKeyAndMarkerWithAbsoluteFiniteExpiration() {
		Instant expiry = Instant.now().plusSeconds(120);
		blacklist.blacklist(jti, expiry);
		var storedKey = ArgumentCaptor.forClass(byte[].class);
		var marker = ArgumentCaptor.forClass(byte[].class);
		var condition = ArgumentCaptor.forClass(SetCondition.class);
		var expiration = ArgumentCaptor.forClass(Expiration.class);
		verify(strings).set(storedKey.capture(), marker.capture(), condition.capture(), expiration.capture());
		assertArrayEquals(key.getBytes(StandardCharsets.UTF_8), storedKey.getValue());
		assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), marker.getValue());
		assertEquals(SetCondition.ifAbsent().getKeyCondition(), condition.getValue().getKeyCondition());
		assertTrue(expiration.getValue().isUnixTimestamp());
		assertFalse(expiration.getValue().isPersistent());
		assertEquals(expiry.toEpochMilli(), expiration.getValue().getExpirationTimeInMilliseconds());
	}

	@Test
	void expiredInputDoesNotWriteOrDeleteExistingEntries() {
		blacklist.blacklist(jti, Instant.now().minusSeconds(1));
		blacklist.blacklist(jti, Instant.EPOCH);
		verifyNoInteractions(redis, strings);
	}

	@Test
	void repeatedWritesUseNxAndAlreadyExistingResultIsSafe() {
		Instant expiry = Instant.now().plusSeconds(120);
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenReturn(true, false);
		blacklist.blacklist(jti, expiry);
		blacklist.blacklist(jti, expiry);
		var condition = ArgumentCaptor.forClass(SetCondition.class);
		var expiration = ArgumentCaptor.forClass(Expiration.class);
		verify(strings, times(2)).set(any(byte[].class), any(byte[].class), condition.capture(), expiration.capture());
		for (var value : condition.getAllValues()) assertEquals(SetCondition.ifAbsent().getKeyCondition(), value.getKeyCondition());
		for (var value : expiration.getAllValues()) assertEquals(expiry.toEpochMilli(), value.getExpirationTimeInMilliseconds());
	}

	@Test
	void lookupUsesOnlyJtiKeyAndReturnsActualExistence() {
		when(redis.hasKey(key)).thenReturn(true, false);
		assertTrue(blacklist.isBlacklisted(jti));
		assertFalse(blacklist.isBlacklisted(jti));
		verify(redis, times(2)).hasKey(key);
	}

	@Test
	void redisReadAndWriteFailuresPropagateUnchanged() {
		var failure = new RedisConnectionFailureException("Test Redis unavailable");
		when(redis.hasKey(key)).thenThrow(failure);
		assertSame(failure, assertThrows(RedisConnectionFailureException.class, () -> blacklist.isBlacklisted(jti)));
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenThrow(failure);
		assertSame(failure, assertThrows(RedisConnectionFailureException.class,
				() -> blacklist.blacklist(jti, Instant.now().plusSeconds(120))));
	}

	@Test
	void nullRedisResponsesFailRatherThanReportSuccessOrMiss() {
		when(redis.hasKey(key)).thenReturn(null);
		assertThrows(IllegalStateException.class, () -> blacklist.isBlacklisted(jti));
		when(strings.set(any(byte[].class), any(byte[].class), any(SetCondition.class), any(Expiration.class)))
				.thenReturn(null);
		assertThrows(IllegalStateException.class, () -> blacklist.blacklist(jti, Instant.now().plusSeconds(120)));
	}

	@Test
	void requiredArgumentsFailBeforeRedisAccess() {
		assertThrows(NullPointerException.class, () -> blacklist.isBlacklisted(null));
		assertThrows(NullPointerException.class, () -> blacklist.blacklist(null, Instant.now().plusSeconds(120)));
		assertThrows(NullPointerException.class, () -> blacklist.blacklist(jti, null));
		verifyNoInteractions(redis, strings);
	}
}
