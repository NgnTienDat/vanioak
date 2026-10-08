package com.h.vanioak.modules.identity.internal.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.application.ApplicationEntity;
import com.h.vanioak.modules.identity.internal.application.ApplicationRepository;
import com.h.vanioak.modules.identity.internal.application.EnvironmentEntity;
import com.h.vanioak.modules.identity.internal.application.EnvironmentRepository;

class ApiKeyServiceTest {
	private final IngestionCredentialRepository credentials = mock(IngestionCredentialRepository.class);
	private final ApplicationRepository applications = mock(ApplicationRepository.class);
	private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
	private final ApiKeyService service = new ApiKeyService(credentials, applications, environments,
			mock(StringRedisTemplate.class), JsonMapper.builder().build(), Duration.ofSeconds(30));
	private final UUID appId = UUID.randomUUID();
	private final UUID envId = UUID.randomUUID();
	private final UUID actor = UUID.randomUUID();
	private final ApplicationEntity application = mock(ApplicationEntity.class);
	private final EnvironmentEntity environment = mock(EnvironmentEntity.class);
	private final List<IngestionCredentialEntity> saved = new ArrayList<>();

	@BeforeEach
	void setup() {
		when(applications.findById(appId)).thenReturn(Optional.of(application));
		when(environments.findById(envId)).thenReturn(Optional.of(environment));
		when(environment.getApplicationId()).thenReturn(appId);
		when(environment.getId()).thenReturn(envId);
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(credentials.saveAndFlush(any())).thenAnswer(call -> {
			IngestionCredentialEntity row = call.getArgument(0);
			if (row.getId() == null) ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
			if (row.getCreatedAt() == null) ReflectionTestUtils.setField(row, "createdAt", Instant.now());
			saved.add(row);
			return row;
		});
	}

	@Test
	void createsIndependentRandomSecretsAndPersistsOnlyPrefixAndSha256() throws Exception {
		var first = service.create(appId, envId, actor, null);
		var second = service.create(appId, envId, actor, null);
		assertTrue(first.apiKey().matches("[0-9a-f]{16}\\.[A-Za-z0-9_-]{43}"));
		assertTrue(!first.apiKey().equals(second.apiKey()));
		assertEquals(32, Base64.getUrlDecoder().decode(first.apiKey().substring(17)).length);
		var row = saved.getFirst();
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(first.apiKey().getBytes(StandardCharsets.UTF_8)));
		assertTrue(hash.equals(row.getKeyHash()));
		assertTrue(row.getKeyHash().matches("[0-9a-f]{64}"));
		assertEquals(first.credential().keyPrefix(), row.getKeyPrefix());
		assertEquals(envId, row.getEnvironmentId());
		assertEquals(actor, row.getCreatedBy());
		assertNull(row.getExpiresAt());
		assertFalse(first.toString().contains(first.apiKey()));
		assertFalse(first.credential().toString().contains(first.apiKey()));
	}

	@Test
	void rotationCreatesReplacementWithSameExpiryAndRevocationIsIdempotent() {
		Instant expiry = Instant.now().plusSeconds(3600);
		var original = service.create(appId, envId, actor, expiry);
		var row = saved.getFirst();
		when(credentials.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
		UUID rotatingAdmin = UUID.randomUUID();
		var replacement = service.rotate(row.getId(), rotatingAdmin);
		assertTrue(!original.apiKey().equals(replacement.apiKey()));
		assertTrue(!original.credential().id().equals(replacement.credential().id()));
		assertEquals(expiry, replacement.credential().expiresAt());
		assertEquals(envId, replacement.credential().environmentId());
		assertEquals(Status.REVOKED, row.getStatus());
		assertEquals(rotatingAdmin, saved.getLast().getCreatedBy());
		Instant revokedAt = row.getRevokedAt();
		assertEquals(revokedAt, service.revoke(row.getId()).revokedAt());
		assertEquals(revokedAt, service.revoke(row.getId()).revokedAt());
		error(ErrorCode.ACCESS_DENIED, () -> service.rotate(row.getId(), actor));
	}

	@Test
	void validatesOwnershipDisabledScopesExpiryAndMissingCredentialsBeforeIssuance() {
		error(ErrorCode.APPLICATION_NOT_FOUND, () -> service.create(UUID.randomUUID(), envId, actor, null));
		error(ErrorCode.ENVIRONMENT_NOT_FOUND, () -> service.create(appId, UUID.randomUUID(), actor, null));
		when(environment.getApplicationId()).thenReturn(UUID.randomUUID());
		error(ErrorCode.ENVIRONMENT_NOT_FOUND, () -> service.create(appId, envId, actor, null));
		when(environment.getApplicationId()).thenReturn(appId);
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		error(ErrorCode.ACCESS_DENIED, () -> service.create(appId, envId, actor, null));
		when(environment.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		error(ErrorCode.ACCESS_DENIED, () -> service.create(appId, envId, actor, null));
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
		error(ErrorCode.INVALID_REQUEST, () -> service.create(appId, envId, actor, Instant.EPOCH));
		error(ErrorCode.API_KEY_NOT_FOUND, () -> service.rotate(UUID.randomUUID(), actor));
		error(ErrorCode.API_KEY_NOT_FOUND, () -> service.revoke(UUID.randomUUID()));
		verify(credentials, never()).saveAndFlush(any());
	}

	@Test
	void listReportsEffectiveExpiryWithoutWritingAndStillAllowsDisabledScope() {
		var row = row(Instant.EPOCH);
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		when(credentials.findCredentials(eq(envId), isNull(), any())).thenReturn(List.of(row));
		var page = service.list(appId, envId, null, 50);
		assertEquals(Status.EXPIRED, page.items().getFirst().status());
		assertEquals(Status.ACTIVE, row.getStatus());
		assertNull(page.nextCursor());
		verify(credentials, never()).saveAndFlush(any());
		when(credentials.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
		assertEquals(Status.REVOKED, service.revoke(row.getId()).status());
		assertEquals(Status.REVOKED, service.list(appId, envId, null, 50).items().getFirst().status());
	}

	@Test
	void scopedPaginationAndExpiredRotationAreHandled() {
		var first = row(Instant.EPOCH);
		var second = row(null);
		when(credentials.findCredentials(eq(envId), isNull(), any())).thenReturn(List.of(first, second));
		var page = service.list(appId, envId, null, 1);
		assertEquals(first.getId().toString(), new String(Base64.getUrlDecoder().decode(page.nextCursor()), StandardCharsets.UTF_8));
		when(credentials.findCredentials(eq(envId), eq(first.getId()), any())).thenReturn(List.of(second));
		assertEquals(second.getId(), service.list(appId, envId, page.nextCursor(), 1).items().getFirst().id());
		error(ErrorCode.INVALID_CURSOR, () -> service.list(appId, envId, "invalid", 50));
		for (int limit : new int[] {0, 101}) error(ErrorCode.INVALID_REQUEST, () -> service.list(appId, envId, null, limit));
		when(credentials.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
		error(ErrorCode.ACCESS_DENIED, () -> service.rotate(first.getId(), actor));
		verify(credentials, never()).saveAndFlush(any());
	}

	@Test
	void unexpectedPersistenceFailurePropagates() {
		var failure = new DataAccessResourceFailureException("Test failure");
		doThrow(failure).when(credentials).saveAndFlush(any());
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> service.create(appId, envId, actor, null)));
	}

	private IngestionCredentialEntity row(Instant expiry) {
		var row = new IngestionCredentialEntity(envId, "test-prefix", "a".repeat(64), actor, expiry);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private void error(ErrorCode expected, org.junit.jupiter.api.function.Executable operation) {
		assertEquals(expected, assertThrows(IdentityException.class, operation).getErrorCode());
	}
}

