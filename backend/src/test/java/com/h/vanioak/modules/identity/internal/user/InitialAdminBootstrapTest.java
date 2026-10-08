package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.h.vanioak.modules.identity.api.UserFacade.Status;

class InitialAdminBootstrapTest {

	private final UserRepository users = mock(UserRepository.class);
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
	private static final String USERNAME = "bootstrap-test-admin";
	private static final String PASSWORD = " test-only-bootstrap-password ";

	@Test
	void createsActiveAdminWithBCryptWithoutNormalizingCredentials() {
		new InitialAdminBootstrap(users, encoder, " " + USERNAME + " ", PASSWORD).run(null);
		var saved = ArgumentCaptor.forClass(UserEntity.class);
		verify(users).saveAndFlush(saved.capture());
		assertEquals(" " + USERNAME + " ", saved.getValue().getUsername());
		assertEquals("ADMIN", saved.getValue().getRole());
		assertEquals(Status.ACTIVE, saved.getValue().getStatus());
		assertNotEquals(PASSWORD, saved.getValue().getPasswordHash());
		assertTrue(saved.getValue().getPasswordHash().startsWith("$2"));
		assertTrue(encoder.matches(PASSWORD, saved.getValue().getPasswordHash()));
	}

	@Test
	void existingActiveAndDisabledAdminsAreUnchangedAcrossRepeatedRuns() {
		for (Status status : Status.values()) {
			var admin = UserEntity.initialAdmin(USERNAME, "existing-hash");
			admin.update(null, null, status);
			when(users.findByUsername(USERNAME)).thenReturn(Optional.of(admin));
			var bootstrap = new InitialAdminBootstrap(users, encoder, USERNAME, null);
			bootstrap.run(null);
			bootstrap.run(null);
			assertEquals("existing-hash", admin.getPasswordHash());
			assertEquals(status, admin.getStatus());
			assertEquals("ADMIN", admin.getRole());
		}
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void engineerUsernameFailsSafelyWithoutChangingAccount() {
		var engineer = new UserEntity(USERNAME, "existing-hash");
		when(users.findByUsername(USERNAME)).thenReturn(Optional.of(engineer));
		var error = assertThrows(IllegalStateException.class,
				() -> new InitialAdminBootstrap(users, encoder, USERNAME, PASSWORD).run(null));
		assertFalse(error.getMessage().contains(USERNAME));
		assertFalse(error.getMessage().contains(PASSWORD));
		assertEquals("ENGINEER", engineer.getRole());
		assertEquals(Status.ACTIVE, engineer.getStatus());
		assertEquals("existing-hash", engineer.getPasswordHash());
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void disabledOrAbsentEnableSettingDoesNotCreateRunnerOrTouchStorage() {
		var context = context();
		context.run(application -> assertFalse(application.containsBean("initialAdminBootstrap")));
		context.withPropertyValues("vanioak.identity.bootstrap-admin.enabled=false")
				.run(application -> assertFalse(application.containsBean("initialAdminBootstrap")));
		verifyNoInteractions(users);
	}

	@Test
	void enabledConfigurationCreatesTheApplicationRunner() {
		context().withPropertyValues("vanioak.identity.bootstrap-admin.enabled=true",
				"vanioak.identity.bootstrap-admin.username=" + USERNAME,
				"vanioak.identity.bootstrap-admin.password=" + PASSWORD.trim())
				.run(application -> {
					assertNull(application.getStartupFailure());
					application.getBean(InitialAdminBootstrap.class).run(null);
					verify(users).findByUsername(USERNAME);
					verify(users).saveAndFlush(any());
				});
	}

	@Test
	void invalidRequiredConfigurationFailsSafelyWithoutSaving() {
		for (String username : new String[] {null, "", " ", "a".repeat(101)}) {
			assertThrows(IllegalStateException.class,
					() -> new InitialAdminBootstrap(users, encoder, username, PASSWORD).run(null));
		}
		verifyNoInteractions(users);
		for (String password : new String[] {null, "", "a".repeat(73)}) {
			var error = assertThrows(IllegalStateException.class,
					() -> new InitialAdminBootstrap(users, encoder, USERNAME, password).run(null));
			assertEquals("Invalid ADMIN bootstrap password configuration", error.getMessage());
			assertNull(error.getCause());
		}
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void persistenceFailurePropagates() {
		var failure = new IllegalStateException("test persistence failure");
		when(users.saveAndFlush(any())).thenThrow(failure);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> new InitialAdminBootstrap(users, encoder, USERNAME, PASSWORD).run(null)));
	}

	private ApplicationContextRunner context() {
		return new ApplicationContextRunner().withUserConfiguration(InitialAdminBootstrap.class)
				.withBean(UserRepository.class, () -> users)
				.withBean(org.springframework.security.crypto.password.PasswordEncoder.class, () -> encoder);
	}
}
