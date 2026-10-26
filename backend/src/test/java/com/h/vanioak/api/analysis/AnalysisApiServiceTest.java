package com.h.vanioak.api.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.analysis.api.AnalysisFacade;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;

class AnalysisApiServiceTest {
	final ApplicationFacade apps = mock(ApplicationFacade.class);
	final ApplicationAccessFacade access = mock(ApplicationAccessFacade.class);
	final AnalysisFacade analysis = mock(AnalysisFacade.class);
	final AnalysisApiService service = new AnalysisApiService(apps, access, analysis);
	final UUID actor = UUID.randomUUID(), app = UUID.randomUUID(), env = UUID.randomUUID();

	org.springframework.security.core.Authentication user(String role) {
		return UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null,
				List.of(new SimpleGrantedAuthority("ROLE_" + role)));
	}

	@Test
	void explicitScopesUseTrustedIdentityGuards() {
		assertEquals(Set.of(app), service.authorize(user("ENGINEER"), app, env).applicationIds());
		verify(access).requireEnvironmentReadAccess(actor, app, env);
		verifyNoInteractions(apps);
	}

	@Test
	void unknownExplicitScopeMapsAdmin400AndEngineer403BeforeQuery() {
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.APPLICATION_NOT_FOUND)).when(access)
				.requireApplicationReadAccess(actor, app);
		assertEquals(ErrorCode.INVALID_QUERY,
				assertThrows(AnalysisException.class, () -> service.authorize(user("ADMIN"), app, null))
						.getErrorCode());
		assertEquals(ErrorCode.ACCESS_DENIED,
				assertThrows(AnalysisException.class, () -> service.authorize(user("ENGINEER"), app, null))
						.getErrorCode());
		verifyNoInteractions(analysis);
	}

	@Test
	void pagedMetadataIncludesDisabledScopesAndEnvironmentOnlyIsReauthorized() {
		var row = new ApplicationFacade.ApplicationView(app, "name", null, ApplicationFacade.Status.DISABLED,
				List.of(new ApplicationFacade.EnvironmentView(env, ApplicationFacade.EnvironmentName.DEV,
						ApplicationFacade.Status.DISABLED)));
		when(apps.list(actor, null, null, 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), "next"));
		when(apps.list(actor, null, "next", 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(row), null));
		var s = service.authorize(user("ENGINEER"), null, env);
		assertEquals(Set.of(app), s.applicationIds());
		verify(access).requireEnvironmentReadAccess(actor, app, env);
	}

	@Test
	void excessiveScopeFailsInsteadOfTruncating() {
		var rows = new ArrayList<ApplicationFacade.ApplicationView>();
		for (int i = 0; i < 1001; i++)
			rows.add(new ApplicationFacade.ApplicationView(UUID.randomUUID(), "a", null,
					ApplicationFacade.Status.ACTIVE, List.of()));
		when(apps.list(actor, null, null, 100)).thenReturn(new ApplicationFacade.ApplicationPage(rows, null));
		assertEquals(ErrorCode.INVALID_QUERY,
				assertThrows(AnalysisException.class, () -> service.authorize(user("ADMIN"), null, null))
						.getErrorCode());
		verifyNoInteractions(analysis);
	}

	@Test
	void emptyScopeAndMetadataFailureNeverBecomeUnrestricted() {
		when(apps.list(actor, null, null, 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), null));
		assertTrue(service.authorize(user("ENGINEER"), null, null).applicationIds().isEmpty());
		when(apps.list(actor, null, null, 100)).thenThrow(new IllegalStateException());
		assertThrows(IllegalStateException.class, () -> service.authorize(user("ENGINEER"), null, null));
	}

	@Test
	void samePrincipalAfterRevokeAndForgedEnvironmentCannotInvokeEitherQuery() {
		var caller = user("ENGINEER");
		var filters = new AnalysisFacade.SearchFilters(null, null, null, null, null, null, 1);
		var health = new AnalysisFacade.HealthFilters(java.time.Instant.now().minusSeconds(1), java.time.Instant.now(),
				"hour");
		service.search(caller, app, null, filters);
		verify(analysis).search(any(), eq(filters));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED)).when(access)
				.requireApplicationReadAccess(actor, app);
		assertThrows(IdentityException.class, () -> service.search(caller, app, null, filters));
		assertThrows(IdentityException.class, () -> service.health(caller, app, null, health));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED)).when(access)
				.requireEnvironmentReadAccess(actor, app, env);
		assertThrows(IdentityException.class, () -> service.health(caller, app, env, health));
		verifyNoMoreInteractions(analysis);
	}
}
