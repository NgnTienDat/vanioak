package com.h.vanioak.api.alert;

import java.util.HashSet;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AlertRuleApiService {
	private final AlertRuleFacade rules;
	private final ApplicationFacade applications;

	public AlertRuleFacade.Rule create(Authentication caller, AlertRuleFacade.Create command) {
		UUID actor = admin(caller);
		requireActiveEnvironment(actor, command.environmentId());
		return rules.create(actor, command);
	}

	public AlertRuleFacade.Page list(Authentication caller, String cursor, int limit) {
		admin(caller);
		return rules.list(cursor, limit);
	}

	public AlertRuleFacade.Rule update(Authentication caller, UUID id, AlertRuleFacade.Update command) {
		UUID actor = admin(caller);
		if (Boolean.TRUE.equals(command.enabled()))
			requireActiveEnvironment(actor, rules.get(id).environmentId());
		return rules.update(id, command);
	}

	public AlertRuleFacade.Rule disable(Authentication caller, UUID id) {
		admin(caller);
		return rules.disable(id);
	}

	private UUID admin(Authentication caller) {
		if (caller == null || !caller.isAuthenticated()
				|| caller.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
			throw new AlertException(ErrorCode.ACCESS_DENIED);
		try { return UUID.fromString(caller.getName()); }
		catch (IllegalArgumentException ex) { throw new AlertException(ErrorCode.ACCESS_DENIED); }
	}

	private void requireActiveEnvironment(UUID actor, UUID environmentId) {
		String cursor = null;
		var cursors = new HashSet<String>();
		do {
			var page = applications.list(actor, null, cursor, 100);
			for (var application : page.items()) {
				for (var environment : application.environments()) {
					if (environment.id().equals(environmentId)) {
						if (application.status() != ApplicationFacade.Status.ACTIVE
								|| environment.status() != ApplicationFacade.Status.ACTIVE)
							throw new AlertException(ErrorCode.ACCESS_DENIED);
						return;
					}
				}
			}
			cursor = page.nextCursor();
			if (cursor != null && !cursors.add(cursor)) throw new AlertException(ErrorCode.UNAVAILABLE);
		} while (cursor != null);
		throw new AlertException(ErrorCode.ACCESS_DENIED);
	}
}
