package com.h.vanioak.modules.identity.internal.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.application.ApplicationRepository;
import com.h.vanioak.modules.identity.internal.application.EnvironmentEntity;
import com.h.vanioak.modules.identity.internal.application.EnvironmentRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ApiKeyService implements ApiKeyFacade {
	private static final String CACHE_PREFIX = "vanioak:identity:api-key-lookup:";
	private static final VerificationResult INVALID = new VerificationResult(false, null, null, null, null, null);
	private final IngestionCredentialRepository credentials;
	private final ApplicationRepository applications;
	private final EnvironmentRepository environments;
	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration cacheTtl;
	private final SecureRandom random = new SecureRandom();

	public ApiKeyService(IngestionCredentialRepository credentials, ApplicationRepository applications,
			EnvironmentRepository environments, StringRedisTemplate redis, ObjectMapper mapper,
			@Value("${vanioak.identity.api-key-cache-ttl}") Duration cacheTtl) {
		if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero() || cacheTtl.toMillis() == 0)
			throw new IllegalArgumentException("API key cache TTL must be positive");
		this.credentials = credentials;
		this.applications = applications;
		this.environments = environments;
		this.redis = redis;
		this.mapper = mapper;
		this.cacheTtl = cacheTtl;
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public VerificationResult verify(String rawApiKey) {
		if (rawApiKey == null || !rawApiKey.matches("[0-9a-f]{16}\\.[A-Za-z0-9_-]{43}"))
			return INVALID;
		String hash = hash(rawApiKey);
		CachedCredential cached = cached(hash);
		if (cached != null && cached.usable(Instant.now()))
			return cached.result();
		Instant deadline = Instant.now().plus(cacheTtl);
		var credential = credentials.findByKeyHash(hash).orElse(null);
		if (credential == null || effectiveStatus(credential) != Status.ACTIVE)
			return INVALID;
		var environment = environments.findById(credential.getEnvironmentId()).orElse(null);
		if (environment == null || environment.getStatus() != ApplicationFacade.Status.ACTIVE)
			return INVALID;
		var application = applications.findById(environment.getApplicationId()).orElse(null);
		if (application == null || application.getStatus() != ApplicationFacade.Status.ACTIVE)
			return INVALID;
		if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(deadline))
			deadline = credential.getExpiresAt();
		var context = new CachedCredential(environment.getApplicationId(), credential.getEnvironmentId(),
				application.getName(), environment.getName(),
				credential.getStatus(), application.getStatus(), environment.getStatus(), credential.getExpiresAt(),
				deadline);
		if (credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(Instant.now()))
			return INVALID;
		populate(hash, context);
		return context.result();
	}

	@Override
	@Transactional
	public IssuedKey create(UUID applicationId, UUID environmentId, UUID createdBy, Instant expiresAt) {
		scope(applicationId, environmentId, true);
		if (expiresAt != null && !expiresAt.isAfter(Instant.now()))
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		return issue(environmentId, createdBy, expiresAt);
	}

	@Override
	public CredentialPage list(UUID applicationId, UUID environmentId, String cursor, int limit) {
		scope(applicationId, environmentId, false);
		if (limit < 1 || limit > 100) throw new IdentityException(ErrorCode.INVALID_REQUEST);
		UUID after = null;
		if (cursor != null) {
			try {
				String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				after = UUID.fromString(decoded);
				if (!after.toString().equals(decoded)) throw new IllegalArgumentException();
			} catch (IllegalArgumentException ex) {
				throw new IdentityException(ErrorCode.INVALID_CURSOR);
			}
		}
		var rows = credentials.findCredentials(environmentId, after, PageRequest.of(0, limit + 1));
		var items = rows.stream().limit(limit).map(this::view).toList();
		String next = rows.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(
				items.getLast().id().toString().getBytes(StandardCharsets.UTF_8)) : null;
		return new CredentialPage(items, next);
	}

	@Override
	@Transactional
	public IssuedKey rotate(UUID credentialId, UUID createdBy) {
		var credential = locked(credentialId);
		var environment = environments.findById(credential.getEnvironmentId())
				.orElseThrow(() -> new IdentityException(ErrorCode.ENVIRONMENT_NOT_FOUND));
		scope(environment.getApplicationId(), environment.getId(), true);
		if (effectiveStatus(credential) != Status.ACTIVE)
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		credential.revoke();
		credentials.saveAndFlush(credential);
		var replacement = issue(credential.getEnvironmentId(), createdBy, credential.getExpiresAt());
		invalidateAfterCommit(credential.getKeyHash());
		return replacement;
	}

	@Override
	@Transactional
	public CredentialView revoke(UUID credentialId) {
		var credential = locked(credentialId);
		credential.revoke();
		var result = view(credentials.saveAndFlush(credential));
		invalidateAfterCommit(credential.getKeyHash());
		return result;
	}

	private IngestionCredentialEntity locked(UUID id) {
		return credentials.findByIdForUpdate(id)
				.orElseThrow(() -> new IdentityException(ErrorCode.API_KEY_NOT_FOUND));
	}

	private EnvironmentEntity scope(UUID applicationId, UUID environmentId, boolean active) {
		var application = applications.findById(applicationId)
				.orElseThrow(() -> new IdentityException(ErrorCode.APPLICATION_NOT_FOUND));
		var environment = environments.findById(environmentId)
				.orElseThrow(() -> new IdentityException(ErrorCode.ENVIRONMENT_NOT_FOUND));
		if (!environment.getApplicationId().equals(applicationId))
			throw new IdentityException(ErrorCode.ENVIRONMENT_NOT_FOUND);
		if (active && (application.getStatus() != ApplicationFacade.Status.ACTIVE
				|| environment.getStatus() != ApplicationFacade.Status.ACTIVE))
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		return environment;
	}

	private IssuedKey issue(UUID environmentId, UUID createdBy, Instant expiresAt) {
		byte[] prefixBytes = new byte[8];
		byte[] secretBytes = new byte[32];
		random.nextBytes(prefixBytes);
		random.nextBytes(secretBytes);
		String prefix = HexFormat.of().formatHex(prefixBytes);
		String key = prefix + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
		var credential = credentials.saveAndFlush(new IngestionCredentialEntity(environmentId, prefix, hash(key), createdBy, expiresAt));
		return new IssuedKey(view(credential), key);
	}

	private String hash(String key) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	private CachedCredential cached(String hash) {
		try {
			String value = redis.opsForValue().get(CACHE_PREFIX + hash);
			return value == null ? null : mapper.readValue(value, CachedCredential.class);
		} catch (RuntimeException ex) {
			log.warn("API key cache lookup unavailable: {}", ex.getClass().getSimpleName());
			return null;
		}
	}

	private void populate(String hash, CachedCredential context) {
		if (!context.deadline().isAfter(Instant.now())) return;
		try {
			byte[] value = mapper.writeValueAsBytes(context);
			Boolean result = redis.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().set(
					(CACHE_PREFIX + hash).getBytes(StandardCharsets.UTF_8), value, SetCondition.upsert(),
					Expiration.unixTimestamp(context.deadline().toEpochMilli(), TimeUnit.MILLISECONDS)));
			if (!Boolean.TRUE.equals(result)) log.warn("API key cache write was not confirmed");
		} catch (RuntimeException ex) {
			log.warn("API key cache population unavailable: {}", ex.getClass().getSimpleName());
		}
	}

	private void invalidateAfterCommit(String hash) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					if (redis.delete(CACHE_PREFIX + hash) == null) log.warn("API key cache invalidation was not confirmed");
				} catch (RuntimeException ex) {
					log.warn("API key cache invalidation unavailable: {}", ex.getClass().getSimpleName());
				}
			}
		});
	}

	private record CachedCredential(UUID applicationId, UUID environmentId, String applicationName,
			ApplicationFacade.EnvironmentName environmentName, Status credentialStatus,
			ApplicationFacade.Status applicationStatus, ApplicationFacade.Status environmentStatus,
			Instant expiresAt, Instant deadline) {
		boolean usable(Instant now) {
			return applicationId != null && environmentId != null && applicationName != null && environmentName != null
					&& credentialStatus == Status.ACTIVE
					&& applicationStatus == ApplicationFacade.Status.ACTIVE
					&& environmentStatus == ApplicationFacade.Status.ACTIVE
					&& deadline != null && deadline.isAfter(now) && (expiresAt == null || expiresAt.isAfter(now));
		}

		VerificationResult result() {
			return new VerificationResult(true, applicationId, environmentId, applicationName, environmentName, deadline);
		}
	}

	private Status effectiveStatus(IngestionCredentialEntity credential) {
		if (credential.getStatus() == Status.ACTIVE && credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(Instant.now()))
			return Status.EXPIRED;
		return credential.getStatus();
	}

	private CredentialView view(IngestionCredentialEntity credential) {
		return new CredentialView(credential.getId(), credential.getEnvironmentId(), credential.getKeyPrefix(),
				effectiveStatus(credential), credential.getExpiresAt(), credential.getCreatedAt(),
				credential.getRevokedAt());
	}
}
