package com.h.vanioak.modules.identity.internal.refreshtoken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenPersistenceIT {

	@Autowired
	private RefreshTokenRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void migrationMapsMetadataAndDatabaseCreationTimestamp() {
		UUID userId = insertUser();
		UUID familyId = UUID.randomUUID();
		RefreshTokenEntity parent = token(userId, familyId, null);
		entityManager.persist(parent);
		entityManager.flush();
		parent.setUsed(true);
		RefreshTokenEntity child = token(userId, familyId, parent.getTokenId());
		entityManager.persist(child);
		entityManager.flush();
		entityManager.clear();

		RefreshTokenEntity stored = repository.findById(child.getTokenId()).orElseThrow();
		assertEquals(familyId, stored.getFamilyId());
		assertEquals(parent.getTokenId(), stored.getParentTokenId());
		assertEquals(userId, stored.getUserId());
		assertEquals(child.getExpiresAt(), stored.getExpiresAt());
		assertNotNull(stored.getCreatedAt());
		assertTrue(stored.getExpiresAt().isAfter(stored.getCreatedAt()));
		assertFalse(stored.isUsed());
		assertFalse(stored.isRevoked());
		RefreshTokenEntity storedParent = repository.findById(parent.getTokenId()).orElseThrow();
		assertNull(storedParent.getParentTokenId());
		assertTrue(storedParent.isUsed());
	}

	@Test
	void tokenIdMustBeUnique() {
		RefreshTokenEntity original = token(insertUser(), UUID.randomUUID(), null);
		entityManager.persist(original);
		entityManager.flush();
		entityManager.clear();
		RefreshTokenEntity duplicate = new RefreshTokenEntity(original.getTokenId(), original.getFamilyId(),
				null, original.getUserId(), original.getExpiresAt());
		assertThrows(PersistenceException.class, () -> {
			entityManager.persist(duplicate);
			entityManager.flush();
		});
	}

	@Test
	void lockedLookupRunsWithinTransaction() {
		RefreshTokenEntity token = token(insertUser(), UUID.randomUUID(), null);
		entityManager.persist(token);
		entityManager.flush();
		entityManager.clear();
		RefreshTokenEntity locked = repository.findByTokenIdForUpdate(token.getTokenId()).orElseThrow();
		assertEquals(token.getTokenId(), locked.getTokenId());
		assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(locked));
		assertTrue(repository.findByTokenIdForUpdate(UUID.randomUUID()).isEmpty());
	}

	@Test
	void revokesWholeFamilyWithoutChangingOtherSessions() {
		UUID userId = insertUser();
		UUID familyId = UUID.randomUUID();
		RefreshTokenEntity parent = token(userId, familyId, null);
		parent.setUsed(true);
		entityManager.persist(parent);
		entityManager.flush();
		RefreshTokenEntity child = token(userId, familyId, parent.getTokenId());
		RefreshTokenEntity otherSession = token(userId, UUID.randomUUID(), null);
		entityManager.persist(child);
		entityManager.persist(otherSession);

		assertEquals(2, repository.revokeFamily(familyId));
		assertTrue(repository.findById(parent.getTokenId()).orElseThrow().isRevoked());
		assertTrue(repository.findById(parent.getTokenId()).orElseThrow().isUsed());
		assertTrue(repository.findById(child.getTokenId()).orElseThrow().isRevoked());
		assertFalse(repository.findById(child.getTokenId()).orElseThrow().isUsed());
		assertFalse(repository.findById(otherSession.getTokenId()).orElseThrow().isRevoked());
		assertEquals(0, repository.revokeFamily(UUID.randomUUID()));
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		entityManager.createNativeQuery("""
				insert into users (id, username, password_hash, role)
				values (:id, :username, 'unused-test-hash', 'ENGINEER')
				""")
				.setParameter("id", userId)
				.setParameter("username", "refresh-token-it-" + userId)
				.executeUpdate();
		return userId;
	}

	private RefreshTokenEntity token(UUID userId, UUID familyId, UUID parentTokenId) {
		return new RefreshTokenEntity(UUID.randomUUID(), familyId, parentTokenId, userId,
				Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS));
	}
}
