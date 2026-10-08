package com.h.vanioak.modules.identity.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade.CreateApplication;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade.UpdateApplication;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ApplicationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplicationManagementIT {
	@Autowired
	private ApplicationFacade applications;
	@Autowired
	private ApplicationRepository repository;
	@MockitoSpyBean
	private EnvironmentRepository environments;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	private final List<String> ownedNames = new ArrayList<>();

	@Test
	void creationCommitsExactlyThreeEnvironmentsAndStatusChangesPreserveThem() {
		var created = applications.create(new CreateApplication(name(), null));
		assertEquals(Status.ACTIVE, created.status());
		assertEquals(3, created.environments().size());
		assertEquals(3, created.environments().stream().map(row -> row.id()).distinct().count());
		var originalIds = created.environments().stream().map(row -> row.id()).toList();
		var rows = transaction().execute(status -> environments.findByApplicationIdIn(List.of(created.id())));
		assertEquals(List.of(EnvironmentName.DEV, EnvironmentName.TEST, EnvironmentName.STAGING),
				rows.stream().map(EnvironmentEntity::getName).sorted().toList());
		assertTrue(rows.stream().allMatch(row -> row.getStatus() == Status.ACTIVE
				&& row.getApplicationId().equals(created.id()) && row.getCreatedAt() != null));
		boolean persisted = transaction().execute(status -> repository.findById(created.id()).isPresent());
		assertTrue(persisted);

		transaction().executeWithoutResult(status -> {
			entityManager.createQuery("update EnvironmentEntity e set e.status = :status where e.id = :id")
					.setParameter("status", Status.DISABLED).setParameter("id", originalIds.getFirst()).executeUpdate();
			entityManager.flush();
			entityManager.clear();
		});
		applications.update(created.id(), new UpdateApplication(null, "updated", Status.DISABLED));
		assertEquals(Status.DISABLED, transaction().execute(status -> repository.findById(created.id()).orElseThrow().getStatus()));
		applications.update(created.id(), new UpdateApplication(null, null, Status.DISABLED));
		assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(IdentityException.class,
				() -> applications.update(created.id(), new UpdateApplication("forbidden", null, Status.ACTIVE))).getErrorCode());
		var active = applications.update(created.id(), new UpdateApplication(null, null, Status.ACTIVE));
		assertEquals(Status.ACTIVE, active.status());
		assertEquals("updated", active.description());
		assertEquals(originalIds, active.environments().stream().map(row -> row.id()).toList());
		assertEquals(Status.DISABLED, active.environments().getFirst().status());
		assertEquals(Status.ACTIVE, active.environments().getLast().status());
	}

	@Test
	void duplicateCreateAndRenameLeaveCommittedRecordsUnchanged() {
		String firstName = name();
		var first = applications.create(new CreateApplication(firstName, "first"));
		var second = applications.create(new CreateApplication(name(), "second"));
		assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(IdentityException.class,
				() -> applications.create(new CreateApplication(firstName, null))).getErrorCode());
		assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(IdentityException.class,
				() -> applications.update(second.id(), new UpdateApplication(firstName, null, null))).getErrorCode());
		assertEquals("second", transaction().execute(status -> repository.findById(second.id()).orElseThrow().getDescription()));
		int count = transaction().execute(status -> environments.findByApplicationIdIn(List.of(first.id(), second.id())).size());
		assertEquals(6, count);
		applications.update(first.id(), new UpdateApplication(null, null, Status.DISABLED));
		applications.update(second.id(), new UpdateApplication(null, null, Status.DISABLED));
		var page = applications.list(Status.DISABLED, null, 1);
		assertEquals(1, page.items().size());
		assertEquals(Status.DISABLED, page.items().getFirst().status());
		assertNotNull(page.nextCursor());
		var next = applications.list(Status.DISABLED, page.nextCursor(), 1);
		assertEquals(1, next.items().size());
		assertNotEquals(page.items().getFirst().id(), next.items().getFirst().id());
	}

	@Test
	void failureAfterRealEnvironmentFlushRollsBackApplicationAndAllEnvironments() {
		String applicationName = name();
		var answer = mockingDetails(environments).getMockCreationSettings().getDefaultAnswer();
		var insertedIds = new ArrayList<UUID>();
		doAnswer(call -> {
			@SuppressWarnings("unchecked")
			List<EnvironmentEntity> rows = (List<EnvironmentEntity>) answer.answer(call);
			insertedIds.addAll(rows.stream().map(EnvironmentEntity::getId).toList());
			assertEquals(3, rows.size());
			throw new IllegalStateException("Test failure after environment flush");
		}).when(environments).saveAllAndFlush(anyList());
		assertThrows(IllegalStateException.class, () -> applications.create(new CreateApplication(applicationName, null)));
		assertEquals(3, insertedIds.size());
		transaction().executeWithoutResult(status -> {
			assertFalse(repository.existsByName(applicationName));
			assertTrue(environments.findAllById(insertedIds).isEmpty());
		});
	}

	private String name() {
		String name = "application-it-" + UUID.randomUUID();
		ownedNames.add(name);
		return name;
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(transactionManager);
	}

	@AfterEach
	void cleanupOwnedApplications() {
		reset(environments);
		transaction().executeWithoutResult(status -> {
			var ids = entityManager.createQuery("select a.id from ApplicationEntity a where a.name in :names", UUID.class)
					.setParameter("names", ownedNames).getResultList();
			if (!ids.isEmpty()) {
				entityManager.createQuery("delete from EnvironmentEntity e where e.applicationId in :ids")
						.setParameter("ids", ids).executeUpdate();
				entityManager.createQuery("delete from ApplicationEntity a where a.id in :ids")
						.setParameter("ids", ids).executeUpdate();
			}
		});
	}
}

