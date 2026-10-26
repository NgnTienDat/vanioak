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
}
