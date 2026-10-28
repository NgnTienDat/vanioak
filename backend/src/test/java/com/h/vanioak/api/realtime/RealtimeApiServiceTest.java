package com.h.vanioak.api.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;

class RealtimeApiServiceTest {
	private final UUID actor = UUID.randomUUID();
	private final UUID app = UUID.randomUUID();
	private final UUID env = UUID.randomUUID();
	private final RealtimeFacade realtime = mock(RealtimeFacade.class);
	private final AuthFacade auth = mock(AuthFacade.class);
	private final ApplicationAccessFacade access = mock(ApplicationAccessFacade.class);
	private final ApplicationFacade applications = mock(ApplicationFacade.class);
	private final RealtimeApiService service = new RealtimeApiService(realtime, auth, access, applications);
	private final RealtimeFacade.Sink sink = new RealtimeFacade.Sink() {
		@Override public void send(String name, String json) { }
		@Override public void heartbeat() { }
		@Override public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }
	};

	@Test
	void exactScopeIsCheckedBeforeAdmissionAndRevokedSameTokenCannotRead() {
		when(auth.authenticateAccessToken("token")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		var caller = caller("ENGINEER");
		service.open(caller, "Bearer token", app, env, "ERROR", sink);
		var guard = captureGuard();
		verify(access).requireEnvironmentReadAccess(actor, app, env);
		assertTrue(guard.mayRead(app, env));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED))
				.when(access).requireEnvironmentReadAccess(actor, app, env);
		assertFalse(guard.mayRead(app, env));
		guard.close();
		assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(RealtimeException.class, guard::requireSession).getErrorCode());
	}

	@Test
	void invalidOrChangedSessionFailsBeforeSubscriptionAndDependencyFailsClosed() {
		when(auth.authenticateAccessToken("token")).thenReturn(new AuthFacade.AuthenticatedUser(UUID.randomUUID(), "ENGINEER"));
		assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(RealtimeException.class,
				() -> service.open(caller("ENGINEER"), "Bearer token", app, env, null, sink)).getErrorCode());
		verifyNoInteractions(realtime);
		when(auth.authenticateAccessToken("token")).thenThrow(new IllegalStateException("dependency unavailable"));
		assertThrows(IllegalStateException.class,
				() -> service.open(caller("ENGINEER"), "Bearer token", app, env, null, sink));
		verifyNoInteractions(realtime);
	}

	@Test
	void environmentOnlyResolvesCurrentAuthorizedParentWithoutExpandingScope() {
		when(auth.authenticateAccessToken("token")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		when(applications.list(eq(actor), isNull(), isNull(), eq(100))).thenReturn(new ApplicationFacade.ApplicationPage(
				List.of(new ApplicationFacade.ApplicationView(app, "app", null, ApplicationFacade.Status.ACTIVE,
						List.of(new ApplicationFacade.EnvironmentView(env, ApplicationFacade.EnvironmentName.DEV,
								ApplicationFacade.Status.ACTIVE)))), "next"));
		when(applications.list(eq(actor), isNull(), eq("next"), eq(100)))
				.thenReturn(new ApplicationFacade.ApplicationPage(List.of(), null));
		service.open(caller("ENGINEER"), "Bearer token", null, env, null, sink);
		verify(access).requireEnvironmentReadAccess(actor, app, env);
		var filters = org.mockito.ArgumentCaptor.forClass(RealtimeFacade.Filters.class);
		verify(realtime).subscribe(eq(actor), filters.capture(), any(), eq(sink));
		assertEquals(app, filters.getValue().applicationId());
		assertEquals(env, filters.getValue().environmentId());
	}

	@Test
	void malformedFilterAndMissingBearerNeverCreateSubscription() {
		assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(RealtimeException.class,
				() -> service.open(caller("ADMIN"), "Bearer token", app, env, "TRACE", sink)).getErrorCode());
		assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(RealtimeException.class,
				() -> service.open(caller("ADMIN"), null, app, env, null, sink)).getErrorCode());
		verifyNoInteractions(realtime, auth, access);
	}

	@Test
	void explicitUnknownAdminPreserves404AndEngineerMismatchIs403() {
		when(auth.authenticateAccessToken("token")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ADMIN"));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.APPLICATION_NOT_FOUND))
				.when(access).requireApplicationReadAccess(actor, app);
		assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, assertThrows(IdentityException.class,
				() -> service.open(caller("ADMIN"), "Bearer token", app, null, null, sink))
				.getErrorCode().getStatus());
		when(auth.authenticateAccessToken("token")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED))
				.when(access).requireEnvironmentReadAccess(actor, app, env);
		assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, assertThrows(IdentityException.class,
				() -> service.open(caller("ENGINEER"), "Bearer token", app, env, null, sink))
				.getErrorCode().getStatus());
		verifyNoInteractions(realtime);
	}

	private RealtimeFacade.Authorization captureGuard() {
		var guard = org.mockito.ArgumentCaptor.forClass(RealtimeFacade.Authorization.class);
		verify(realtime).subscribe(eq(actor), any(), guard.capture(), eq(sink));
		return guard.getValue();
	}

	private UsernamePasswordAuthenticationToken caller(String role) {
		return UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null,
				List.of(new SimpleGrantedAuthority("ROLE_" + role)));
	}
}
