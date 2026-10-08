package com.h.vanioak.modules.identity.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.modules.identity.api.ApplicationFacade.CreateApplication;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentView;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade.UpdateApplication;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

class ApplicationServiceTest {
	private final ApplicationRepository applications = mock(ApplicationRepository.class);
	private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
	private final ApplicationService service = new ApplicationService(applications, environments);
	private final UUID id = UUID.randomUUID();

	@BeforeEach
	void savesReturnAssignedEntities() {
		lenient().when(applications.saveAndFlush(any())).thenAnswer(call -> {
			ApplicationEntity entity = call.getArgument(0);
			if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", id);
			return entity;
		});
		lenient().when(environments.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void createHasExactlyThreeActiveEnvironmentsAndPreservesInput() {
		var result = service.create(new CreateApplication(" app ", null));
		assertEquals(" app ", result.name());
		assertNull(result.description());
		assertEquals(Status.ACTIVE, result.status());
		assertEquals(List.of(EnvironmentName.DEV, EnvironmentName.TEST, EnvironmentName.STAGING),
				result.environments().stream().map(EnvironmentView::name).toList());
		assertTrue(result.environments().stream().allMatch(row -> row.status() == Status.ACTIVE));
		verify(environments).saveAllAndFlush(anyList());
	}

	@Test
	void invalidNameAndDuplicateAre400WithoutEnvironmentWrites() {
		for (String name : new String[] {null, "a".repeat(101)}) {
			error(ErrorCode.INVALID_REQUEST, () -> service.create(new CreateApplication(name, null)));
		}
		when(applications.existsByName("duplicate")).thenReturn(true);
		error(ErrorCode.INVALID_REQUEST, () -> service.create(new CreateApplication("duplicate", null)));
		verifyNoInteractions(environments);
		service.create(new CreateApplication("a".repeat(100), ""));
		service.create(new CreateApplication("", null));
	}

	@Test
	void onlyApplicationNameUniqueConstraintIsTranslatedAndOtherFailuresPropagate() {
		var duplicate = new DataIntegrityViolationException("test",
				new ConstraintViolationException("test", new SQLException("test", "23505"), "applications_name_key"));
		doThrow(duplicate).when(applications).saveAndFlush(any());
		error(ErrorCode.INVALID_REQUEST, () -> service.create(new CreateApplication("race", null)));
		var unexpected = new DataIntegrityViolationException("test infrastructure");
		doThrow(unexpected).when(applications).saveAndFlush(any());
		assertSame(unexpected, assertThrows(DataIntegrityViolationException.class,
				() -> service.create(new CreateApplication("other", null))));
		verifyNoInteractions(environments);
	}

	@Test
	void listUsesCursorStatusAndOneEnvironmentBatch() {
		var first = application();
		var second = new ApplicationEntity("second", null);
		ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
		var environment = new EnvironmentEntity(id, EnvironmentName.DEV);
		when(applications.findApplications(eq(Status.DISABLED), isNull(), any())).thenReturn(List.of(first, second));
		when(environments.findByApplicationIdIn(List.of(id))).thenReturn(List.of(environment));
		var page = service.list(Status.DISABLED, null, 1);
		assertEquals(1, page.items().size());
		assertEquals(id.toString(), new String(Base64.getUrlDecoder().decode(page.nextCursor())));
		assertEquals(1, page.items().getFirst().environments().size());
		when(applications.findApplications(eq(Status.DISABLED), eq(id), any())).thenReturn(List.of());
		assertTrue(service.list(Status.DISABLED, page.nextCursor(), 1).items().isEmpty());
		verify(environments, times(1)).findByApplicationIdIn(any());
		error(ErrorCode.INVALID_CURSOR, () -> service.list(null, "invalid", 50));
		for (int limit : new int[] {0, 101}) error(ErrorCode.INVALID_REQUEST, () -> service.list(null, null, limit));
	}

	@Test
	void updatesAndReactivationPreserveEnvironmentStatusAndDisabledMetadataIsDenied() {
		var application = application();
		var environment = new EnvironmentEntity(id, EnvironmentName.TEST);
		ReflectionTestUtils.setField(environment, "status", Status.DISABLED);
		when(applications.findById(id)).thenReturn(Optional.of(application));
		when(environments.findByApplicationIdIn(List.of(id))).thenReturn(List.of(environment));
		var updated = service.update(id, new UpdateApplication("renamed", "", Status.DISABLED));
		assertEquals("renamed", updated.name());
		assertEquals("", updated.description());
		assertEquals(Status.DISABLED, updated.environments().getFirst().status());
		assertEquals(Status.DISABLED, service.update(id, new UpdateApplication(null, null, Status.DISABLED)).status());
		error(ErrorCode.ACCESS_DENIED, () -> service.update(id, new UpdateApplication("other", null, Status.ACTIVE)));
		assertEquals(Status.ACTIVE, service.update(id, new UpdateApplication(null, null, Status.ACTIVE)).status());
		assertEquals("renamed", service.update(id, new UpdateApplication(null, null, null)).name());
		assertEquals(Status.DISABLED, environment.getStatus());
		verify(environments, never()).saveAllAndFlush(any());
	}

	@Test
	void missingApplicationDuplicateRenameAndEnvironmentFailureAreHandled() {
		error(ErrorCode.APPLICATION_NOT_FOUND, () -> service.update(id, new UpdateApplication(null, null, Status.DISABLED)));
		var application = application();
		when(applications.findById(id)).thenReturn(Optional.of(application));
		when(applications.existsByName("duplicate")).thenReturn(true);
		error(ErrorCode.INVALID_REQUEST, () -> service.update(id, new UpdateApplication("duplicate", null, null)));
		assertEquals("original", application.getName());
		var failure = new IllegalStateException("test environment failure");
		when(environments.saveAllAndFlush(anyList())).thenThrow(failure);
		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(new CreateApplication("new", null))));
	}

	private ApplicationEntity application() {
		var application = new ApplicationEntity("original", "description");
		ReflectionTestUtils.setField(application, "id", id);
		return application;
	}

	private void error(ErrorCode expected, org.junit.jupiter.api.function.Executable operation) {
		assertEquals(expected, assertThrows(IdentityException.class, operation).getErrorCode());
	}
}

