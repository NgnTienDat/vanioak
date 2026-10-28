package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class RedisAlertFrequency {
	private static final DefaultRedisScript<Long> RECONCILE = new DefaultRedisScript<>("""
			redis.call('DEL', KEYS[1])
			for i=2,#ARGV,2 do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i+1]) end
			redis.call('EXPIRE', KEYS[1], ARGV[1])
			return redis.call('ZCARD', KEYS[1])
			""", Long.class);
	private final StringRedisTemplate redis;

	public void reconcile(UUID env, String fingerprint, UUID rule, int seconds, List<AlertEventReceipt> window) {
		var arguments = new ArrayList<String>(); arguments.add(Integer.toString(seconds));
		for (var receipt : window) {
			arguments.add(Long.toString(micros(receipt.getReceivedAt())));
			arguments.add(receipt.getEventId().toString());
		}
		String key = "vanioak:alert:frequency:" + env + ":" + fingerprint + ":" + rule;
		Long count = redis.execute(RECONCILE, List.of(key), arguments.toArray());
		if (count == null || count != window.size()) throw new AlertException(ErrorCode.UNAVAILABLE);
	}

	static long micros(Instant value) {
		long micros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1000);
		if (micros < -9_007_199_254_740_991L || micros > 9_007_199_254_740_991L)
			throw new IllegalArgumentException("Received time exceeds exact Redis score range");
		return micros;
	}
}
