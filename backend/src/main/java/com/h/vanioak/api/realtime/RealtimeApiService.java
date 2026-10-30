package com.h.vanioak.api.realtime;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class RealtimeApiService {
	private final RealtimeFacade realtime;
	private final AuthFacade auth;
	private final ApplicationAccessFacade access;
	private final ApplicationFacade applications;

	public RealtimeFacade.Subscription open(Authentication caller, String header, UUID applicationId,
			UUID environmentId, String level, RealtimeFacade.Sink sink) {
		if (caller == null || !caller.isAuthenticated()) throw new RealtimeException(ErrorCode.UNAUTHENTICATED);
		if (level != null && !Set.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level))
			throw new RealtimeException(ErrorCode.INVALID_REQUEST);
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank())
			throw new RealtimeException(ErrorCode.UNAUTHENTICATED);
		UUID actor;
		try { actor = UUID.fromString(caller.getName()); }
		catch (IllegalArgumentException ex) { throw new RealtimeException(ErrorCode.UNAUTHENTICATED); }
		String token = header.substring(7).trim();
		RealtimeAccessGuard guard = new RealtimeAccessGuard(actor, applicationId, environmentId, token, auth, access);
		try {
			guard.requireSession();
			UUID scopedApp = applicationId;
			if (applicationId == null && environmentId != null) {
				scopedApp = parent(actor, environmentId, caller);
				guard.close();
				guard = new RealtimeAccessGuard(actor, scopedApp, environmentId, token, auth, access);
				guard.requireSession();
			}
			guard.requireExplicitScope();
			return realtime.subscribe(actor, new RealtimeFacade.Filters(scopedApp, environmentId, level), guard, sink);
		} catch (RuntimeException ex) {
			guard.close();
			throw ex;
		}
	}

	private UUID parent(UUID actor, UUID environmentId, Authentication caller) {
		String cursor = null;
		Set<String> cursors = new HashSet<>();
		Set<UUID> seen = new HashSet<>();
		UUID parent = null;
		int pages = 0;
		do {
			if (++pages > 11) throw new RealtimeException(ErrorCode.INVALID_REQUEST);
			var page = applications.list(actor, null, cursor, 100);
			for (var app : page.items()) {
				if (!seen.add(app.id())) throw new IllegalStateException("Duplicate application page");
				if (seen.size() > 1000) throw new RealtimeException(ErrorCode.INVALID_REQUEST);
				if (app.environments().stream().anyMatch(env -> environmentId.equals(env.id()))) {
					if (parent != null) throw new IllegalStateException("Duplicate environment parent");
					parent = app.id();
				}
			}
			cursor = page.nextCursor();
			if (cursor != null && !cursors.add(cursor)) throw new IllegalStateException("Repeated application cursor");
		} while (cursor != null);
		if (parent != null) return parent;
		boolean admin = caller.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		throw new IdentityException(admin ? com.h.vanioak.modules.identity.api.ErrorCode.ENVIRONMENT_NOT_FOUND
				: com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED);
	}
}
