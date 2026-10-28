package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.IdentityException;

class AlertRuleApiServiceTest {
	final AlertRuleFacade rules = mock(AlertRuleFacade.class);
	final ApplicationFacade apps = mock(ApplicationFacade.class);
	final AlertRuleApiService api = new AlertRuleApiService(rules, apps);
	final UUID actor = UUID.randomUUID(), env = UUID.randomUUID(), id = UUID.randomUUID();
	final UsernamePasswordAuthenticationToken admin = UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
	final AlertRuleFacade.Create create = new AlertRuleFacade.Create(env, AlertRuleFacade.Level.ERROR, 1, 60, 300, 300, true);
	ApplicationFacade.ApplicationPage page(ApplicationFacade.Status appStatus, ApplicationFacade.Status envStatus) {
		return new ApplicationFacade.ApplicationPage(List.of(new ApplicationFacade.ApplicationView(UUID.randomUUID(), "app", null, appStatus,
				List.of(new ApplicationFacade.EnvironmentView(env, ApplicationFacade.EnvironmentName.TEST, envStatus)))), null);
	}
	AlertRuleFacade.Rule rule() { return new AlertRuleFacade.Rule(id, env, AlertRuleFacade.Level.ERROR, 1, 60, 300, 300, true, Instant.EPOCH, Instant.EPOCH); }

	@Test void allUseCasesDenyEngineerBeforeAnyDataAccess() {
		var engineer = UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ENGINEER")));
		assertThrows(AlertException.class, () -> api.create(engineer, create));
		assertThrows(AlertException.class, () -> api.list(engineer, null, 50));
		assertThrows(AlertException.class, () -> api.update(engineer, id, new AlertRuleFacade.Update(null, null, null, null, true)));
		assertThrows(AlertException.class, () -> api.disable(engineer, id));
		assertThrows(AlertException.class, () -> api.list(null, null, 50)); verifyNoInteractions(rules, apps);
	}

	@Test void createResolvesEnvironmentAcrossPagesAndUsesAuthenticatedCreator() {
		when(apps.list(actor, null, null, 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), "next"));
		when(apps.list(actor, null, "next", 100)).thenReturn(page(ApplicationFacade.Status.ACTIVE, ApplicationFacade.Status.ACTIVE));
		when(rules.create(actor, create)).thenReturn(rule()); assertEquals(rule(), api.create(admin, create));
		verify(rules).create(actor, create);
	}

	@Test void disabledApplicationOrEnvironmentBlocksCreationEvenOfDisabledRule() {
		for (var states : List.of(List.of(ApplicationFacade.Status.DISABLED, ApplicationFacade.Status.ACTIVE),
				List.of(ApplicationFacade.Status.ACTIVE, ApplicationFacade.Status.DISABLED))) {
			when(apps.list(actor, null, null, 100)).thenReturn(page(states.get(0), states.get(1)));
			assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AlertException.class, () -> api.create(admin, create)).getErrorCode());
			assertThrows(AlertException.class, () -> api.create(admin, new AlertRuleFacade.Create(env, create.level(), 1, 1, 1, 1, false)));
		}
		verifyNoInteractions(rules);
	}

	@Test void missingEnvironmentNeverWrites() {
		when(apps.list(actor, null, null, 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), null));
		assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AlertException.class, () -> api.create(admin, create)).getErrorCode()); verifyNoInteractions(rules);
	}

	@Test void enablingChecksStoredEnvironmentAndRejectsDisabledScope() {
		when(rules.get(id)).thenReturn(rule());
		when(apps.list(actor, null, null, 100)).thenReturn(page(ApplicationFacade.Status.DISABLED, ApplicationFacade.Status.ACTIVE));
		assertThrows(AlertException.class, () -> api.update(admin, id, new AlertRuleFacade.Update(null, null, null, null, true)));
		verify(rules, never()).update(any(), any());
	}

	@Test void enablingActiveScopeSucceeds() {
		when(rules.get(id)).thenReturn(rule());
		when(apps.list(actor, null, null, 100)).thenReturn(page(ApplicationFacade.Status.ACTIVE, ApplicationFacade.Status.ACTIVE));
		var patch = new AlertRuleFacade.Update(null, null, null, null, true); api.update(admin, id, patch); verify(rules).update(id, patch);
	}

	@Test void listNumericPatchAndDisablingDoNotRequireActiveScope() {
		api.list(admin, "cursor", 10); api.disable(admin, id);
		var patch = new AlertRuleFacade.Update(5, null, null, null, false); api.update(admin, id, patch);
		api.update(admin, id, new AlertRuleFacade.Update(6, null, null, null, null));
		verify(rules).list("cursor", 10); verify(rules).disable(id); verify(rules).update(id, patch); verifyNoInteractions(apps);
	}

	@Test void metadataFailureOrCursorCycleCannotAuthorizeWrites() {
		when(apps.list(actor, null, null, 100)).thenThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED));
		assertThrows(IdentityException.class, () -> api.create(admin, create));
		doReturn(new ApplicationFacade.ApplicationPage(List.of(), "loop")).when(apps).list(actor, null, null, 100);
		when(apps.list(actor, null, "loop", 100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(), "loop"));
		assertEquals(ErrorCode.UNAVAILABLE, assertThrows(AlertException.class, () -> api.create(admin, create)).getErrorCode()); verifyNoInteractions(rules);
	}
}
