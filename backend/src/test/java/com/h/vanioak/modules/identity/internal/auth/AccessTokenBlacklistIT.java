package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SpringBootTest(classes = { AccessTokenBlacklist.class, DataRedisAutoConfiguration.class },
		webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessTokenBlacklistIT {

	@Autowired
	private AccessTokenBlacklist blacklist;
	@Autowired
	private StringRedisTemplate redis;
	private final List<String> ownedKeys = new ArrayList<>();

	@AfterEach
	void cleanupOwnedKeys() {
		if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
	}

	@Test
	void realRedisStoresMarkerUntilAbsoluteExpiryAndPreservesDeadlineOnRepeats() {
		UUID jti = UUID.randomUUID();
		String key = own(jti);
		Instant expiry = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(120);
		assertFalse(blacklist.isBlacklisted(jti));
		blacklist.blacklist(jti, expiry);
		assertTrue(blacklist.isBlacklisted(jti));
		assertEquals("1", redis.opsForValue().get(key));
		assertEquals(expiry.toEpochMilli(), deadline(key));
		long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
		assertTrue(ttl > 0 && ttl <= 120_000);
		for (Instant repeatedExpiry : List.of(expiry, expiry.minusSeconds(30), expiry.plusSeconds(30))) {
			blacklist.blacklist(jti, repeatedExpiry);
			assertEquals(expiry.toEpochMilli(), deadline(key));
			assertEquals("1", redis.opsForValue().get(key));
			assertTrue(blacklist.isBlacklisted(jti));
		}
		blacklist.blacklist(jti, Instant.EPOCH);
		assertEquals(expiry.toEpochMilli(), deadline(key));
	}

	@Test
	void expiredTokenCreatesNoEntry() {
		UUID jti = UUID.randomUUID();
		String key = own(jti);
		blacklist.blacklist(jti, Instant.now().minusSeconds(1));
		assertFalse(blacklist.isBlacklisted(jti));
		assertEquals(-2L, redis.getExpire(key, TimeUnit.MILLISECONDS));
	}

	private String own(UUID jti) {
		String key = "vanioak:identity:access-blacklist:" + jti;
		ownedKeys.add(key);
		return key;
	}

	private Long deadline(String key) {
		return redis.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
	}
}
