package com.h.vanioak.modules.alert.internal.engine;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade;
import com.h.vanioak.modules.alert.api.IncidentResolutionFacade;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.EnvironmentScopeFacade;

@Service
public class ManualIncidentResolutionService implements IncidentResolutionFacade {
	private final IncidentRepository incidents;
	private final IncidentHistoryRepository history;
	private final AlertScopeLock locks;
	private final ApplicationAccessFacade access;
	private final EnvironmentScopeFacade scopes;
	private final IncidentRealtimePublisher realtime;
	private final Clock clock;
	private final TransactionTemplate transactions;

	@Autowired
	public ManualIncidentResolutionService(IncidentRepository incidents, IncidentHistoryRepository history,
			AlertScopeLock locks, ApplicationAccessFacade access, EnvironmentScopeFacade scopes,
			IncidentRealtimePublisher realtime, PlatformTransactionManager manager) {
		this(incidents, history, locks, access, scopes, realtime, manager, Clock.systemUTC());
	}

	ManualIncidentResolutionService(IncidentRepository incidents, IncidentHistoryRepository history,
			AlertScopeLock locks, ApplicationAccessFacade access, EnvironmentScopeFacade scopes,
			IncidentRealtimePublisher realtime, PlatformTransactionManager manager, Clock clock) {
		this.incidents = incidents; this.history = history; this.locks = locks;
		this.access = access; this.scopes = scopes; this.realtime = realtime; this.clock = clock;
		transactions = new TransactionTemplate(manager);
		transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public Optional<Resolution> resolve(IncidentReadFacade.Scope scope, UUID incidentId) {
		if (scope == null || scope.actorId() == null || incidentId == null) throw new AlertException(ErrorCode.INVALID_REQUEST);
		if (scope.environmentIds().isEmpty()) return Optional.empty();
		return Objects.requireNonNull(transactions.execute(tx -> {
			var target = incidents.resolutionScope(incidentId, scope.environmentIds());
			if (target.isEmpty()) return Optional.<Resolution>empty();
			locks.scope(target.get().getEnvironmentId(), target.get().getFingerprint());
			var current = incidents.readDetail(incidentId, scope.environmentIds());
			if (current.isEmpty()) return Optional.<Resolution>empty();
			var incident = current.get();
			var application = scopes.findApplicationId(incident.getEnvironmentId())
					.orElseThrow(() -> new AlertException(ErrorCode.ACCESS_DENIED));
			access.requireEnvironmentReadAccess(scope.actorId(), application, incident.getEnvironmentId());
			if (incident.getStatus() == IncidentEntity.Status.OPEN) {
				var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
				incident.resolveManually(now, scope.actorId());
				realtime.register(incident, history.save(new IncidentHistory(incident, IncidentHistory.Type.RESOLVED, now)));
			}
			return Optional.of(new Resolution(incident.getId(), IncidentReadFacade.Status.valueOf(incident.getStatus().name()),
					incident.getResolvedAt(), incident.getResolutionSource(), incident.getResolvedByUserId()));
		}));
	}
}
