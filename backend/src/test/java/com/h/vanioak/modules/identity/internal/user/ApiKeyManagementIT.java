package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.apikey.ApiKeyService;
import com.h.vanioak.modules.identity.internal.apikey.IngestionCredentialEntity;
import com.h.vanioak.modules.identity.internal.apikey.IngestionCredentialRepository;
import com.h.vanioak.modules.identity.internal.application.ApplicationService;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ApiKeyService.class, ApplicationService.class, SecurityConfig.class, ApiKeyManagementIT.JsonConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyManagementIT {
	@TestConfiguration
	static class JsonConfiguration {
		@Bean
		ObjectMapper mapper() { return JsonMapper.builder().build(); }
	}
	@MockitoBean
	private StringRedisTemplate redis;
	@Autowired
	private ApiKeyFacade keys;
	@Autowired
	private ApplicationFacade applications;
	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder encoder;
	@MockitoSpyBean
	private IngestionCredentialRepository credentials;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	private UUID actor;
	private UUID appId;
	private UUID envId;
	private final List<UUID> ownedApplications = new ArrayList<>();
	private final List<UUID> ownedEnvironments = new ArrayList<>();

	@BeforeEach
	void committedFixtures() {
		actor = transaction().execute(status -> users.saveAndFlush(UserEntity.initialAdmin(
				"apikey-it-" + UUID.randomUUID(), encoder.encode("test-only-admin-password"))).getId());
		var application = application();
		appId = application.id();
		envId = application.environments().getFirst().id();
	}

	@Test
	void committedCreateRotateListAndRevokeStoreOnlyVerificationMaterial() throws Exception {
		Instant expiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
		var first = keys.create(appId, envId, actor, expiry);
		var row = row(first.credential().id());
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(first.apiKey().getBytes(StandardCharsets.UTF_8)));
		assertTrue(hash.equals(row.getKeyHash()));
		assertTrue(row.getKeyHash().matches("[0-9a-f]{64}"));
		assertEquals(actor, row.getCreatedBy());
		assertEquals(envId, row.getEnvironmentId());
		assertEquals(expiry, row.getExpiresAt());
		assertFalse(first.credential().toString().contains(first.apiKey()));
		var replacement = keys.rotate(row.getId(), actor);
		assertNotEquals(row.getId(), replacement.credential().id());
		assertTrue(!first.apiKey().equals(replacement.apiKey()));
		assertEquals(Status.REVOKED, row(row.getId()).getStatus());
		assertEquals(expiry, row(replacement.credential().id()).getExpiresAt());
		assertEquals(actor, row(replacement.credential().id()).getCreatedBy());
		var page = keys.list(appId, envId, null, 1);
		assertEquals(1, page.items().size());
		var next = keys.list(appId, envId, page.nextCursor(), 1);
		assertEquals(1, next.items().size());
		assertNotEquals(page.items().getFirst().id(), next.items().getFirst().id());
		assertTrue(keys.list(appId, ownedEnvironments.get(1), null, 50).items().isEmpty());
		keys.revoke(replacement.credential().id());
		Instant revokedAt = row(replacement.credential().id()).getRevokedAt();
		keys.revoke(replacement.credential().id());
		assertEquals(revokedAt, row(replacement.credential().id()).getRevokedAt());
		assertEquals(2, keys.list(appId, envId, null, 50).items().size());
	}

	@Test
	void ownershipDisabledScopesAndEffectiveExpiryAreEnforced() {
		var other = application();
		assertEquals(ErrorCode.ENVIRONMENT_NOT_FOUND, assertThrows(IdentityException.class,
				() -> keys.create(other.id(), envId, actor, null)).getErrorCode());
		var issued = keys.create(appId, envId, actor, null);
		transaction().executeWithoutResult(status -> {
			entityManager.createQuery("update EnvironmentEntity e set e.status = :status where e.id = :id")
					.setParameter("status", ApplicationFacade.Status.DISABLED).setParameter("id", envId).executeUpdate();
		});
		assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(IdentityException.class,
				() -> keys.rotate(issued.credential().id(), actor)).getErrorCode());
		assertEquals(1, keys.list(appId, envId, null, 50).items().size());
		assertEquals(Status.REVOKED, keys.revoke(issued.credential().id()).status());
		var expiring = keys.create(appId, ownedEnvironments.get(1), actor, Instant.now().plusSeconds(3600));
		transaction().executeWithoutResult(status -> entityManager.createQuery(
				"update IngestionCredentialEntity c set c.expiresAt = :expiry where c.id = :id")
				.setParameter("expiry", Instant.EPOCH).setParameter("id", expiring.credential().id()).executeUpdate());
		assertEquals(Status.EXPIRED, keys.list(appId, ownedEnvironments.get(1), null, 50).items().getFirst().status());
		assertEquals(Status.ACTIVE, row(expiring.credential().id()).getStatus());
		applications.update(appId, new ApplicationFacade.UpdateApplication(null, null, ApplicationFacade.Status.DISABLED));
		assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(IdentityException.class,
				() -> keys.create(appId, ownedEnvironments.get(1), actor, null)).getErrorCode());
	}

	@Test
	void failedReplacementPersistenceRollsBackOldCredentialRevocation() {
		var issued = keys.create(appId, envId, actor, null);
		var answer = mockingDetails(credentials).getMockCreationSettings().getDefaultAnswer();
		var inserted = new ArrayList<UUID>();
		doAnswer(call -> {
			IngestionCredentialEntity entity = call.getArgument(0);
			Object result = answer.answer(call);
			if (!entity.getId().equals(issued.credential().id())) {
				inserted.add(entity.getId());
				throw new IllegalStateException("Test failure after replacement flush");
			}
			return result;
		}).when(credentials).saveAndFlush(any());
		assertThrows(IllegalStateException.class, () -> keys.rotate(issued.credential().id(), actor));
		assertEquals(1, inserted.size());
		assertEquals(Status.ACTIVE, row(issued.credential().id()).getStatus());
		boolean replacementExists = transaction().execute(status -> credentials.findById(inserted.getFirst()).isPresent());
		assertFalse(replacementExists);
		assertEquals(1, keys.list(appId, envId, null, 50).items().size());
	}

	private ApplicationFacade.ApplicationView application() {
		var application = applications.create(new ApplicationFacade.CreateApplication("apikey-app-it-" + UUID.randomUUID(), null));
		ownedApplications.add(application.id());
		ownedEnvironments.addAll(application.environments().stream().map(row -> row.id()).toList());
		return application;
	}

	private IngestionCredentialEntity row(UUID id) {
		return transaction().execute(status -> credentials.findById(id).orElseThrow());
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(transactionManager);
	}

	@AfterEach
	void cleanupOwnedFixtures() {
		reset(credentials);
		transaction().executeWithoutResult(status -> {
			if (!ownedEnvironments.isEmpty()) {
				entityManager.createQuery("delete from IngestionCredentialEntity c where c.environmentId in :ids")
						.setParameter("ids", ownedEnvironments).executeUpdate();
				entityManager.createQuery("delete from EnvironmentEntity e where e.id in :ids")
						.setParameter("ids", ownedEnvironments).executeUpdate();
			}
			if (!ownedApplications.isEmpty()) entityManager.createQuery("delete from ApplicationEntity a where a.id in :ids")
					.setParameter("ids", ownedApplications).executeUpdate();
			if (actor != null) users.deleteById(actor);
		});
	}
}

