package com.h.vanioak.modules.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class IngestionService implements IngestionFacade {
	private final ApiKeyFacade identity;
	private final RawLogPublisher publisher;
	private final Duration cacheTtl;
	private final int maxSize;
	private final Clock clock;
	private final LinkedHashMap<String, CachedCredential> cache = new LinkedHashMap<>(16, 0.75f, true);

	@Autowired
	public IngestionService(ApiKeyFacade identity, RawLogPublisher publisher,
			@Value("${vanioak.ingestion.api-key-local-cache-ttl}") Duration cacheTtl,
			@Value("${vanioak.ingestion.api-key-local-cache-max-size}") int maxSize) {
		this(identity, publisher, cacheTtl, maxSize, Clock.systemUTC());
	}

	IngestionService(ApiKeyFacade identity, RawLogPublisher publisher, Duration cacheTtl, int maxSize, Clock clock) {
		if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative() || maxSize < 1)
			throw new IllegalArgumentException("API key local cache TTL and size must be positive");
		this.identity = identity;
		this.publisher = publisher;
		this.cacheTtl = cacheTtl;
		this.maxSize = maxSize;
		this.clock = clock;
	}

	@Override
	public VerificationResult authenticate(String rawApiKey) {
		if (rawApiKey == null || rawApiKey.isEmpty()) throw new IngestionException(ErrorCode.INVALID_API_KEY);
		String fingerprint = fingerprint(rawApiKey);
		// ponytail: one lock for short map operations; shard only if contention becomes measurable.
		synchronized (cache) {
			var hit = cache.get(fingerprint);
			if (hit != null && hit.expiresAt().isAfter(clock.instant())) return hit.context();
			cache.remove(fingerprint);
		}
		VerificationResult context;
		try {
			context = identity.verify(rawApiKey);
		} catch (RuntimeException ex) {
			log.warn("Identity credential lookup unavailable: {}", ex.getClass().getSimpleName());
			throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
		}
		if (context != null && !context.valid()) throw new IngestionException(ErrorCode.INVALID_API_KEY);
		requireUsable(context);
		Instant expiry = clock.instant().plus(cacheTtl);
		if (context.validUntil().isBefore(expiry)) expiry = context.validUntil();
		synchronized (cache) {
			cache.put(fingerprint, new CachedCredential(context, expiry));
			if (cache.size() > maxSize) cache.pollFirstEntry();
		}
		return context;
	}

	@Override
	public void validateBinding(VerificationResult context, List<LogScope> scopes) {
		requireUsable(context);
		for (LogScope scope : scopes) {
			if (!context.applicationName().equals(scope.application()) || scope.environment() == null
					|| !context.environmentName().name().equals(scope.environment().toUpperCase(Locale.ROOT)))
				throw new IngestionException(ErrorCode.INVALID_API_KEY);
		}
	}

	@Override
	public Acceptance ingest(VerificationResult context, List<LogEntry> logs) {
		validateBinding(context, logs.stream().map(log -> new LogScope(log.application(), log.environment())).toList());
		UUID requestId = UUID.randomUUID();
		Instant receivedAt = clock.instant();
		var items = logs.stream().map(log -> new RawLogBatchEvent.Log(UUID.randomUUID(), log.hostIp(), log.level(),
				log.message(), log.timestamp(), log.traceId(), log.metadata() == null ? Map.of()
						: Collections.unmodifiableMap(new LinkedHashMap<>(log.metadata())))).toList();
		var event = new RawLogBatchEvent(UUID.randomUUID(), "raw.log", receivedAt, "ingestion", 1,
				new RawLogBatchEvent.Payload(requestId, context.applicationId(), context.environmentId(), receivedAt, items));
		publisher.publish(event);
		return new Acceptance(requestId, items.size());
	}

	private void requireUsable(VerificationResult context) {
		if (context == null || !context.valid() || context.applicationId() == null || context.environmentId() == null
				|| context.applicationName() == null || context.environmentName() == null || context.validUntil() == null
				|| !context.validUntil().isAfter(clock.instant()))
			throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
	}

	private String fingerprint(String rawApiKey) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(rawApiKey.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	private record CachedCredential(VerificationResult context, Instant expiresAt) { }
}
