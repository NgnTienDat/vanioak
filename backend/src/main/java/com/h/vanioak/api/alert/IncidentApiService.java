package com.h.vanioak.api.alert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import com.h.vanioak.api.alert.dto.IncidentResponse;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.IdentityException;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class IncidentApiService {
	private final IncidentReadFacade incidents;
	private final ApplicationFacade applications;
	private final ApplicationAccessFacade access;

	public IncidentResponse.Page list(Authentication caller, UUID app, UUID env, IncidentReadFacade.Filters filters) {
		var authorized=authorize(caller,app,env);
		var page=incidents.list(authorized.scope(),filters);
		return new IncidentResponse.Page(page.items().stream().map(i -> response(i,null,authorized.references())).toList(),page.nextCursor());
	}

	public IncidentResponse detail(Authentication caller, UUID id) {
		var authorized=authorize(caller,null,null);
		var detail=incidents.detail(authorized.scope(),id).orElseThrow(() -> new AlertException(
				authorized.admin() ? ErrorCode.INCIDENT_NOT_FOUND : ErrorCode.ACCESS_DENIED));
		var events=detail.events().stream().map(e -> new IncidentResponse.History(e.id(),e.eventType(),e.eventAt(),e.errorCount(),e.message(),e.metadata())).toList();
		return response(detail.incident(),events,authorized.references());
	}

	private record References(IncidentResponse.Ref application, IncidentResponse.Ref environment) { }
	private record Authorized(IncidentReadFacade.Scope scope, Map<UUID,References> references, boolean admin) { }

	private Authorized authorize(Authentication caller, UUID app, UUID env) {
		if (caller==null || !caller.isAuthenticated()) throw new AlertException(ErrorCode.ACCESS_DENIED);
		boolean admin=caller.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		if (!admin && caller.getAuthorities().stream().noneMatch(a -> "ROLE_ENGINEER".equals(a.getAuthority())))
			throw new AlertException(ErrorCode.ACCESS_DENIED);
		UUID actor;
		try { actor=UUID.fromString(caller.getName()); }
		catch (IllegalArgumentException ex) { throw new AlertException(ErrorCode.ACCESS_DENIED); }
		try {
			if (app!=null) {
				if (env==null) access.requireApplicationReadAccess(actor,app);
				else access.requireEnvironmentReadAccess(actor,app,env);
			}
		} catch (IdentityException ex) {
			if (ex.getErrorCode()==com.h.vanioak.modules.identity.api.ErrorCode.APPLICATION_NOT_FOUND
					|| ex.getErrorCode()==com.h.vanioak.modules.identity.api.ErrorCode.ENVIRONMENT_NOT_FOUND)
				throw new AlertException(ErrorCode.ACCESS_DENIED);
			throw ex;
		}
		var refs=new HashMap<UUID,References>(); var appIds=new HashSet<UUID>(); var cursors=new HashSet<String>();
		String cursor=null;
		do {
			var page=applications.list(actor,null,cursor,100);
			for (var application:page.items()) {
				if (!appIds.add(application.id())) throw new AlertException(ErrorCode.UNAVAILABLE);
				if (app!=null && !app.equals(application.id())) continue;
				for (var environment:application.environments()) {
					if (env!=null && !env.equals(environment.id())) continue;
					var reference=new References(new IncidentResponse.Ref(application.id(),application.name()),
							new IncidentResponse.Ref(environment.id(),environment.name().name()));
					if (refs.putIfAbsent(environment.id(),reference)!=null) throw new AlertException(ErrorCode.UNAVAILABLE);
				}
			}
			cursor=page.nextCursor();
			if (cursor!=null && !cursors.add(cursor)) throw new AlertException(ErrorCode.UNAVAILABLE);
		} while (cursor!=null);
		if ((app!=null || env!=null) && refs.isEmpty()) throw new AlertException(ErrorCode.ACCESS_DENIED);
		if (env!=null && app==null) access.requireEnvironmentReadAccess(actor,refs.get(env).application().id(),env);
		return new Authorized(new IncidentReadFacade.Scope(actor,refs.keySet(),app,env),Map.copyOf(refs),admin);
	}

	private IncidentResponse response(IncidentReadFacade.Item item, List<IncidentResponse.History> events, Map<UUID,References> references) {
		var ref=references.get(item.environmentId());
		if (ref==null) throw new AlertException(ErrorCode.UNAVAILABLE);
		return new IncidentResponse(item.id(),ref.application(),ref.environment(),item.level(),item.status(),item.fingerprint(),item.message(),
				item.errorCount(),item.firstSeenAt(),item.lastSeenAt(),item.resolvedAt(),events);
	}
}
