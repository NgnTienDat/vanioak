package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.UserFacade.Status;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SecurityConfig.class)
class InitialAdminBootstrapIT {

	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private EntityManager entityManager;

	@Test
	void persistsAdminAndPreservesExistingDisabledAdminOnRepeatedBootstrap() {
		var other = users.saveAndFlush(new UserEntity("bootstrap-other-it-" + UUID.randomUUID(),
				encoder.encode("test-only-existing-password")));
		UUID otherId = other.getId();
		String username = "bootstrap-it-" + UUID.randomUUID();
		String password = "test-only-bootstrap-password";
		var bootstrap = new InitialAdminBootstrap(users, encoder, username, password);
		bootstrap.run(null);
		entityManager.clear();
		var created = users.findByUsername(username).orElseThrow();
		UUID id = created.getId();
		String hash = created.getPasswordHash();
		assertEquals("ADMIN", created.getRole());
		assertEquals(Status.ACTIVE, created.getStatus());
		assertNotEquals(password, hash);
		assertTrue(encoder.matches(password, hash));
		bootstrap.run(null);
		created.update(null, null, Status.DISABLED);
		users.saveAndFlush(created);
		new InitialAdminBootstrap(users, encoder, username, null).run(null);
		entityManager.clear();
		var existing = users.findByUsername(username).orElseThrow();
		assertEquals(id, existing.getId());
		assertEquals(hash, existing.getPasswordHash());
		assertEquals(Status.DISABLED, existing.getStatus());
		assertEquals("ADMIN", existing.getRole());
		assertEquals("ENGINEER", users.findById(otherId).orElseThrow().getRole());
	}

	@Test
	void engineerConflictDoesNotPromoteOrResetAnExistingAccount() {
		String username = "bootstrap-conflict-it-" + UUID.randomUUID();
		String hash = encoder.encode("test-only-existing-password");
		var engineer = users.saveAndFlush(new UserEntity(username, hash));
		UUID id = engineer.getId();
		assertThrows(IllegalStateException.class,
				() -> new InitialAdminBootstrap(users, encoder, username, "test-only-new-password").run(null));
		entityManager.clear();
		var existing = users.findByUsername(username).orElseThrow();
		assertEquals(id, existing.getId());
		assertEquals("ENGINEER", existing.getRole());
		assertEquals(Status.ACTIVE, existing.getStatus());
		assertEquals(hash, existing.getPasswordHash());
	}
}
