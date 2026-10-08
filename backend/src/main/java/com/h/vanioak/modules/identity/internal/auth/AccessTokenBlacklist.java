package com.h.vanioak.modules.identity.internal.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccessTokenBlacklist {

	private static final String PREFIX = "vanioak:identity:access-blacklist:";
	private final StringRedisTemplate redis;

	public void blacklist(UUID jti, Instant expiresAt) {
		String key = key(jti);
		Objects.requireNonNull(expiresAt, "Access token expiration is required");
		if (!expiresAt.isAfter(Instant.now())) return;
		Boolean result = redis.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().set(
				key.getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8),
				SetCondition.ifAbsent(), Expiration.unixTimestamp(expiresAt.toEpochMilli(), TimeUnit.MILLISECONDS)));
		if (result == null) throw new IllegalStateException("Redis blacklist write result is unavailable");
	}

	public boolean isBlacklisted(UUID jti) {
		Boolean result = redis.hasKey(key(jti));
		if (result == null) throw new IllegalStateException("Redis blacklist lookup result is unavailable");
		return result;
	}

	private String key(UUID jti) {
		return PREFIX + Objects.requireNonNull(jti, "Access token JTI is required");
	}
}
