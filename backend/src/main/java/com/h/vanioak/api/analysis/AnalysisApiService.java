package com.h.vanioak.api.analysis;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.analysis.api.AnalysisFacade;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AnalysisApiService {
	private final ApplicationFacade applications;
	private final ApplicationAccessFacade access;
	private final AnalysisFacade analysis;

	public AnalysisFacade.HealthResult health(Authentication caller, UUID app, UUID env,
			AnalysisFacade.HealthFilters filters) {
		return analysis.health(authorize(caller, app, env), filters);
	}

	public AnalysisFacade.SearchPage search(Authentication caller, UUID app, UUID env,
			AnalysisFacade.SearchFilters filters) {
		return analysis.search(authorize(caller, app, env), filters);
	}

	AuthorizedScope authorize(Authentication caller, UUID app, UUID env) {
		if (caller == null || !caller.isAuthenticated())
			throw new AnalysisException(ErrorCode.ACCESS_DENIED);
		UUID actor = UUID.fromString(caller.getName());
		boolean admin = caller.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		if (!admin && caller.getAuthorities().stream().noneMatch(a -> "ROLE_ENGINEER".equals(a.getAuthority())))
			throw new AnalysisException(ErrorCode.ACCESS_DENIED);
		try {
			if (app != null) {
				if (env == null)
					access.requireApplicationReadAccess(actor, app);
				else
					access.requireEnvironmentReadAccess(actor, app, env);
				return new AuthorizedScope(actor, Set.of(app), app, env);
			}
			var visible = new HashSet<UUID>();
			UUID parent = null;
			String cursor = null;
			var cursors = new HashSet<String>();
			int pages = 0;
			do {
				if (++pages > 11)
					throw new AnalysisException(ErrorCode.INVALID_QUERY);
				var page = applications.list(actor, null, cursor, 100);
				for (var row : page.items()) {
					if (!visible.add(row.id()))
						throw new AnalysisException(ErrorCode.UNAVAILABLE);
					if (visible.size() > 1000)
						throw new AnalysisException(ErrorCode.INVALID_QUERY);
					if (env != null && row.environments().stream().anyMatch(e -> env.equals(e.id()))) {
						if (parent != null)
							throw new AnalysisException(ErrorCode.UNAVAILABLE);
						parent = row.id();
					}
				}
				cursor = page.nextCursor();
				if (cursor != null && !cursors.add(cursor))
					throw new AnalysisException(ErrorCode.UNAVAILABLE);
			} while (cursor != null);
			if (env != null) {
				if (parent == null)
					throw new AnalysisException(admin ? ErrorCode.INVALID_QUERY : ErrorCode.ACCESS_DENIED);
				access.requireEnvironmentReadAccess(actor, parent, env);
				return new AuthorizedScope(actor, Set.of(parent), null, env);
			}
			return new AuthorizedScope(actor, visible, null, null);
		} catch (IdentityException ex) {
			if (ex.getErrorCode() == com.h.vanioak.modules.identity.api.ErrorCode.APPLICATION_NOT_FOUND
					|| ex.getErrorCode() == com.h.vanioak.modules.identity.api.ErrorCode.ENVIRONMENT_NOT_FOUND)
				throw new AnalysisException(admin ? ErrorCode.INVALID_QUERY : ErrorCode.ACCESS_DENIED);
			throw ex;
		}
	}
}
