package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.h.vanioak.modules.identity.api.UserFacade.CreateUser;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.api.UserFacade.UpdateUser;

@DataJpaTest(showSql = false, properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserService.class)
class UserManagementIT {

	@Autowired
	private UserService users;
	@Autowired
	private UserRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void persistsHashedPasswordAndSoftDeletesWithoutRemovingRow() {
		String username = "identity-it-" + UUID.randomUUID();
		var created = users.create(new CreateUser(username, "integration-password"));
		entityManager.clear();
		UserEntity stored = repository.findById(created.id()).orElseThrow();
		assertNotEquals("integration-password", stored.getPasswordHash());
		assertTrue(new BCryptPasswordEncoder().matches("integration-password", stored.getPasswordHash()));
		assertNotNull(stored.getCreatedAt());
		assertEquals("ENGINEER", users.get(created.id()).role());
		users.update(created.id(), new UpdateUser(username + "-updated", "replacement-password", null));
		users.disable(created.id());
		entityManager.clear();
		stored = repository.findById(created.id()).orElseThrow();
		assertEquals(Status.DISABLED, stored.getStatus());
		assertTrue(new BCryptPasswordEncoder().matches("replacement-password", stored.getPasswordHash()));
		assertEquals(Status.DISABLED, users.disable(created.id()).status());
		assertEquals(Status.ACTIVE, users.update(created.id(), new UpdateUser(null, null, Status.ACTIVE)).status());
	}

	@Test
	void keysetQueryAcceptsNullAndNonNullFiltersAndCursor() {
		for (int i = 0; i < 3; i++) {
			users.create(new CreateUser("identity-page-it-" + UUID.randomUUID(), "integration-password"));
		}
		var first = users.list(null, null, 1);
		assertEquals(1, first.items().size());
		assertNotNull(first.nextCursor());
		var second = users.list(null, first.nextCursor(), 1);
		assertEquals(1, second.items().size());
		assertTrue(first.items().getFirst().id().toString().compareTo(second.items().getFirst().id().toString()) < 0);
		var active = users.list(Status.ACTIVE, null, 100);
		assertTrue(active.items().stream().allMatch(user -> user.status() == Status.ACTIVE && user.role().equals("ENGINEER")));
	}
}
