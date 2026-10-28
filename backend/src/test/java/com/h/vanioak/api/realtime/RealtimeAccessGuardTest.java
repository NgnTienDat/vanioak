package com.h.vanioak.api.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.IdentityException;

class RealtimeAccessGuardTest {
	private final UUID actor = UUID.randomUUID();
	private final UUID app = UUID.randomUUID();
	private final UUID env = UUID.randomUUID();
	private final AuthFacade auth = mock(AuthFacade.class);
	private final ApplicationAccessFacade access = mock(ApplicationAccessFacade.class);

	@Test
	void sameBearerIsCheckedAgainAndRevocationDisableExpiryFailClosed() {
		var guard = new RealtimeAccessGuard(actor, app, env, "secret", auth, access);
		when(auth.authenticateAccessToken("secret")).thenReturn(new AuthFacade.AuthenticatedUser(actor, "ENGINEER"));
		guard.requireSession();
		guard.requireExplicitScope();
		verify(access).requireEnvironmentReadAccess(actor, app, env);
		for (var code : new com.h.vanioak.modules.identity.api.ErrorCode[] {
				com.h.vanioak.modules.identity.api.ErrorCode.INVALID_CREDENTIALS,
				com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED }) {
			doThrow(new IdentityException(code)).when(auth).authenticateAccessToken("secret");
			assertEquals(code, assertThrows(IdentityException.class, guard::requireSession).getErrorCode());
		}
		doThrow(new IllegalStateException("Redis unavailable")).when(auth).authenticateAccessToken("secret");
		assertThrows(IllegalStateException.class, guard::requireSession);
		guard.close();
		verify(auth, times(4)).authenticateAccessToken("secret");
	}

	@Test
	void membershipIsCheckedPerCandidateWithoutPositiveCache() {
		var guard = new RealtimeAccessGuard(actor, null, null, "secret", auth, access);
		assertTrue(guard.mayRead(app, env));
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED))
				.when(access).requireEnvironmentReadAccess(actor, app, env);
		assertFalse(guard.mayRead(app, env));
		verify(access, times(2)).requireEnvironmentReadAccess(actor, app, env);
	}
}
