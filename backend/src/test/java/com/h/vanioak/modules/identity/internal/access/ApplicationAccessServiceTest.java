package com.h.vanioak.modules.identity.internal.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.internal.application.ApplicationRepository;
import com.h.vanioak.modules.identity.internal.application.EnvironmentRepository;
import com.h.vanioak.modules.identity.internal.application.ApplicationEntity;
import com.h.vanioak.modules.identity.internal.application.EnvironmentEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;

class ApplicationAccessServiceTest {
	private final UserRepository users = mock(UserRepository.class);
	private final ApplicationRepository apps = mock(ApplicationRepository.class);
	private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
	private final UserApplicationAccessRepository access = mock(UserApplicationAccessRepository.class);
	private final ApplicationAccessService service = new ApplicationAccessService(users, apps, environments, access);
	private final UUID admin = UUID.randomUUID(), engineer = UUID.randomUUID(), app = UUID.randomUUID(), env = UUID.randomUUID();
	private final UserEntity actor = mock(UserEntity.class), target = mock(UserEntity.class);
	private final ApplicationEntity application = mock(ApplicationEntity.class);
	@BeforeEach void setup() {
		when(users.findById(admin)).thenReturn(Optional.of(actor));
		when(actor.getRole()).thenReturn("ADMIN"); when(actor.getStatus()).thenReturn(UserFacade.Status.ACTIVE);
		when(users.findById(engineer)).thenReturn(Optional.of(target));
		when(target.getRole()).thenReturn("ENGINEER"); when(target.getStatus()).thenReturn(UserFacade.Status.ACTIVE);
		when(apps.findById(app)).thenReturn(Optional.of(application)); when(apps.existsById(app)).thenReturn(true);
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.ACTIVE);
	}
	private void error(ErrorCode code, Runnable call) { assertEquals(code, assertThrows(IdentityException.class, call::run).getErrorCode()); }
	@Test void adminCanAssignAndRevokeIdempotently() {
		assertEquals(new ApplicationAccessFacade.Assignment(engineer, app), service.assign(admin, app, engineer));
		service.assign(admin, app, engineer);service.revoke(admin, app, engineer);service.revoke(admin, app, engineer);
		verify(access,times(2)).assign(engineer,app);verify(access,times(2)).revoke(engineer,app);
	}
	@Test void engineerCannotMutateOrExposeTargetMetadata() {
		error(ErrorCode.ACCESS_DENIED,()->service.assign(engineer,app,engineer));
		error(ErrorCode.ACCESS_DENIED,()->service.revoke(engineer,app,admin));verifyNoInteractions(apps,access,environments);
	}
	@Test void disabledTargetCannotBeAssignedButCanBeRevoked() {
		when(target.getStatus()).thenReturn(UserFacade.Status.DISABLED);
		error(ErrorCode.ACCESS_DENIED,()->service.assign(admin,app,engineer));verify(access,never()).assign(any(),any());
		service.revoke(admin,app,engineer);verify(access).revoke(engineer,app);
	}
	@Test void disabledApplicationCannotBeAssignedButAllowsRemovalAndHistoricalReads() {
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		error(ErrorCode.ACCESS_DENIED,()->service.assign(admin,app,engineer));service.revoke(admin,app,engineer);
		when(access.existsByUserIdAndApplicationId(engineer,app)).thenReturn(true);service.requireApplicationReadAccess(engineer,app);
	}
	@Test void invalidTargetsKeepExistingErrors() {
		error(ErrorCode.ADMIN_USER_FORBIDDEN,()->service.assign(admin,app,admin));
		when(users.findById(engineer)).thenReturn(Optional.empty());error(ErrorCode.USER_NOT_FOUND,()->service.assign(admin,app,engineer));
		when(apps.findById(app)).thenReturn(Optional.empty());error(ErrorCode.APPLICATION_NOT_FOUND,()->service.assign(admin,app,engineer));
		when(apps.existsById(app)).thenReturn(false);error(ErrorCode.APPLICATION_NOT_FOUND,()->service.revoke(admin,app,engineer));verifyNoInteractions(access);
	}
	@Test void disabledMissingAndUnknownRoleCallerFailClosed() {
		when(actor.getStatus()).thenReturn(UserFacade.Status.DISABLED);error(ErrorCode.ACCESS_DENIED,()->service.assign(admin,app,engineer));
		when(actor.getStatus()).thenReturn(UserFacade.Status.ACTIVE);when(actor.getRole()).thenReturn("OTHER");error(ErrorCode.ACCESS_DENIED,()->service.currentUser(admin));
		when(users.findById(admin)).thenReturn(Optional.empty());error(ErrorCode.INVALID_CREDENTIALS,()->service.currentUser(admin));error(ErrorCode.INVALID_CREDENTIALS,()->service.currentUser(null));verifyNoInteractions(access,apps);
	}
	@Test void unassignedEngineerIsDeniedBeforeApplicationLookup() {
		error(ErrorCode.ACCESS_DENIED,()->service.requireApplicationReadAccess(engineer,app));verifyNoInteractions(apps,environments);
	}
	@Test void revocationIsVisibleOnNextCheck() {
		when(access.existsByUserIdAndApplicationId(engineer,app)).thenReturn(true,false);
		service.requireApplicationReadAccess(engineer,app);error(ErrorCode.ACCESS_DENIED,()->service.requireApplicationReadAccess(engineer,app));
	}
	@Test void environmentParentIsRequiredEvenForAdmin() {
		var environment=mock(EnvironmentEntity.class);when(environments.findById(env)).thenReturn(Optional.of(environment));
		when(environment.getApplicationId()).thenReturn(UUID.randomUUID());when(access.existsByUserIdAndApplicationId(engineer,app)).thenReturn(true);
		error(ErrorCode.ACCESS_DENIED,()->service.requireEnvironmentReadAccess(engineer,app,env));error(ErrorCode.ACCESS_DENIED,()->service.requireEnvironmentReadAccess(admin,app,env));
		when(environment.getApplicationId()).thenReturn(app);service.requireEnvironmentReadAccess(engineer,app,env);service.requireEnvironmentReadAccess(admin,app,env);
	}
	@Test void absentEnvironmentDoesNotExposeMetadataToEngineer() {
		when(access.existsByUserIdAndApplicationId(engineer,app)).thenReturn(true);
		error(ErrorCode.ACCESS_DENIED,()->service.requireEnvironmentReadAccess(engineer,app,env));error(ErrorCode.ENVIRONMENT_NOT_FOUND,()->service.requireEnvironmentReadAccess(admin,app,env));
	}
	@Test void dependencyFailureDoesNotGrantAccess() {
		when(access.existsByUserIdAndApplicationId(engineer,app)).thenThrow(new IllegalStateException());assertThrows(IllegalStateException.class,()->service.requireApplicationReadAccess(engineer,app));verifyNoInteractions(apps);
	}

	private UserApplicationAccessRepository.AssignedEngineerRow row(UUID id, String username, UserFacade.Status status) {
		var row = mock(UserApplicationAccessRepository.AssignedEngineerRow.class);
		when(row.getId()).thenReturn(id); when(row.getUsername()).thenReturn(username); when(row.getStatus()).thenReturn(status);
		return row;
	}

	@Test void rosterIncludesAssignedActiveAndDisabledEngineersOnDisabledApplication() {
		when(application.getStatus()).thenReturn(ApplicationFacade.Status.DISABLED);
		var active = row(engineer, "active", UserFacade.Status.ACTIVE);
		var disabled = row(env, "disabled", UserFacade.Status.DISABLED);
		when(access.findAssignedEngineers(app, null, PageRequest.of(0, 51)))
				.thenReturn(List.of(active, disabled));
		var page = service.listEngineers(admin, app, null, 50);
		assertEquals(List.of(new ApplicationAccessFacade.AssignedEngineer(engineer, "active", UserFacade.Status.ACTIVE),
				new ApplicationAccessFacade.AssignedEngineer(env, "disabled", UserFacade.Status.DISABLED)), page.items());
		assertNull(page.nextCursor());
		verify(access, never()).assign(any(), any()); verify(access, never()).revoke(any(), any());
		verifyNoInteractions(environments);
	}

	@Test void emptyRosterIsDistinctFromNonexistentApplication() {
		when(access.findAssignedEngineers(app, null, PageRequest.of(0, 51))).thenReturn(List.of());
		assertTrue(service.listEngineers(admin, app, null, 50).items().isEmpty());
		assertNull(service.listEngineers(admin, app, null, 50).nextCursor());
		UUID missing = UUID.randomUUID();
		error(ErrorCode.APPLICATION_NOT_FOUND, () -> service.listEngineers(admin, missing, null, 50));
		verify(access, never()).findAssignedEngineers(org.mockito.ArgumentMatchers.eq(missing), any(), any());
	}

	@Test void rosterRequiresCurrentActiveAdminBeforeInspectingApplicationOrAssignments() {
		error(ErrorCode.ACCESS_DENIED, () -> service.listEngineers(engineer, app, null, 50));
		when(actor.getStatus()).thenReturn(UserFacade.Status.DISABLED);
		error(ErrorCode.ACCESS_DENIED, () -> service.listEngineers(admin, app, null, 50));
		when(users.findById(admin)).thenReturn(Optional.empty());
		error(ErrorCode.INVALID_CREDENTIALS, () -> service.listEngineers(admin, app, null, 50));
		verifyNoInteractions(apps, access, environments);
	}

	@Test void rosterCursorUsesLastReturnedIdAndReadsOnlyLimitPlusOne() {
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID third = UUID.fromString("80000000-0000-0000-0000-000000000003");
		var one = row(first, "one", UserFacade.Status.ACTIVE);
		var two = row(second, "two", UserFacade.Status.DISABLED);
		var three = row(third, "three", UserFacade.Status.ACTIVE);
		when(access.findAssignedEngineers(app, null, PageRequest.of(0, 3))).thenReturn(List.of(one, two, three));
		when(access.findAssignedEngineers(app, second, PageRequest.of(0, 3))).thenReturn(List.of(three));
		var head = service.listEngineers(admin, app, null, 2);
		assertEquals(List.of(first, second), head.items().stream().map(ApplicationAccessFacade.AssignedEngineer::id).toList());
		assertEquals(second.toString(), new String(Base64.getUrlDecoder().decode(head.nextCursor()), StandardCharsets.UTF_8));
		var tail = service.listEngineers(admin, app, head.nextCursor(), 2);
		assertEquals(List.of(third), tail.items().stream().map(ApplicationAccessFacade.AssignedEngineer::id).toList());
		assertNull(tail.nextCursor());
		verify(access).findAssignedEngineers(app, second, PageRequest.of(0, 3));
	}

	@Test void rosterRejectsInvalidLimitsAndCursorsBeforeRepositoryRead() {
		for (int limit : new int[] {0, -1, 101}) error(ErrorCode.INVALID_REQUEST, () -> service.listEngineers(admin, app, null, limit));
		for (String cursor : List.of("", "!", "a".repeat(65),
				Base64.getUrlEncoder().encodeToString("1-1-1-1-1".getBytes(StandardCharsets.UTF_8)),
				Base64.getUrlEncoder().encodeToString("ABCDEFAB-0000-0000-0000-000000000000".getBytes(StandardCharsets.UTF_8))))
			error(ErrorCode.INVALID_CURSOR, () -> service.listEngineers(admin, app, cursor, 50));
		verifyNoInteractions(access);
	}

	@Test void rosterScopeIsApplicationBoundEvenWhenCursorCameFromAnotherApplication() {
		UUID other = UUID.randomUUID();
		when(apps.existsById(other)).thenReturn(true);
		var first = row(engineer, "first-app", UserFacade.Status.ACTIVE);
		var more = row(env, "more", UserFacade.Status.DISABLED);
		var otherEngineer = row(env, "other-app", UserFacade.Status.DISABLED);
		when(access.findAssignedEngineers(app, null, PageRequest.of(0, 2)))
				.thenReturn(List.of(first, more));
		when(access.findAssignedEngineers(other, engineer, PageRequest.of(0, 101)))
				.thenReturn(List.of(otherEngineer));
		String cursor = service.listEngineers(admin, app, null, 1).nextCursor();
		assertEquals("other-app", service.listEngineers(admin, other, cursor, 100).items().getFirst().username());
		verify(access).findAssignedEngineers(other, engineer, PageRequest.of(0, 101));
	}
}
