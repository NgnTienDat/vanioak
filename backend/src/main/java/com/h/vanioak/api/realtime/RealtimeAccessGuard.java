package com.h.vanioak.api.realtime;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;

final class RealtimeAccessGuard implements RealtimeFacade.Authorization {
	private final UUID actorId;
	private final UUID applicationId;
	private final UUID environmentId;
	private final AuthFacade auth;
	private final ApplicationAccessFacade access;
	private final AtomicReference<String> bearer;

	RealtimeAccessGuard(UUID actorId, UUID applicationId, UUID environmentId, String token,
			AuthFacade auth, ApplicationAccessFacade access) {
		this.actorId = actorId;
		this.applicationId = applicationId;
		this.environmentId = environmentId;
		this.auth = auth;
		this.access = access;
		this.bearer = new AtomicReference<>(token);
	}

	@Override
	public void requireSession() {
		String token = bearer.get();
		if (token == null) throw new RealtimeException(ErrorCode.UNAUTHENTICATED);
		var current = auth.authenticateAccessToken(token);
		if (current == null || !actorId.equals(current.id())
				|| !("ADMIN".equals(current.role()) || "ENGINEER".equals(current.role())))
			throw new RealtimeException(ErrorCode.ACCESS_DENIED);
	}

	@Override
	public void requireExplicitScope() {
		if (applicationId == null) return;
		if (environmentId == null) access.requireApplicationReadAccess(actorId, applicationId);
		else access.requireEnvironmentReadAccess(actorId, applicationId, environmentId);
	}

	@Override
	public boolean mayRead(UUID app, UUID env) {
		try {
			access.requireEnvironmentReadAccess(actorId, app, env);
			return true;
		} catch (IdentityException ex) {
			if (ex.getErrorCode() == com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED
					|| ex.getErrorCode() == com.h.vanioak.modules.identity.api.ErrorCode.APPLICATION_NOT_FOUND
					|| ex.getErrorCode() == com.h.vanioak.modules.identity.api.ErrorCode.ENVIRONMENT_NOT_FOUND)
				return false;
			throw ex;
		}
	}

	@Override
	public void close() { bearer.set(null); }
}
