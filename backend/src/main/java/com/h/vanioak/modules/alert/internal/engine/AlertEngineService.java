package com.h.vanioak.modules.alert.internal.engine;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.internal.rule.AlertRuleRepository;

@Service
public class AlertEngineService implements AlertEngineFacade {
	private final AlertRuleRepository rules;
	private final AlertReceiptRepository receipts;
	private final IncidentRepository incidents;
	private final IncidentHistoryRepository history;
	private final AlertScopeLock locks;
	private final RedisAlertFrequency frequency;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final IncidentRealtimePublisher realtime;

	@Autowired
	public AlertEngineService(AlertRuleRepository rules, AlertReceiptRepository receipts, IncidentRepository incidents,
			IncidentHistoryRepository history, AlertScopeLock locks, RedisAlertFrequency frequency, PlatformTransactionManager manager,
			IncidentRealtimePublisher realtime) {
		this(rules, receipts, incidents, history, locks, frequency, manager, Clock.systemUTC(), realtime);
	}

	AlertEngineService(AlertRuleRepository rules, AlertReceiptRepository receipts, IncidentRepository incidents,
			IncidentHistoryRepository history, AlertScopeLock locks, RedisAlertFrequency frequency,
			PlatformTransactionManager manager, Clock clock, IncidentRealtimePublisher realtime) {
		this.rules = rules; this.receipts = receipts; this.incidents = incidents; this.history = history;
		this.locks = locks; this.frequency = frequency; this.clock = clock;
		this.realtime = realtime;
		transactions = new TransactionTemplate(manager);
		transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public Result evaluate(CriticalEvent event) {
		if (event == null || event.eventId() == null || event.environmentId() == null || event.level() == null
				|| event.message() == null || event.receivedAt() == null) throw new AlertException(ErrorCode.INVALID_REQUEST);
		Instant received = event.receivedAt().truncatedTo(ChronoUnit.MICROS);
		try { RedisAlertFrequency.micros(received); }
		catch (ArithmeticException | IllegalArgumentException ex) { throw new AlertException(ErrorCode.INVALID_REQUEST); }
		String fingerprint = AlertFingerprint.of(event.level(), event.message());
		return Objects.requireNonNull(transactions.execute(tx -> account(event, received, fingerprint)));
	}

	private Result account(CriticalEvent event, Instant received, String fingerprint) {
		locks.event(event.eventId());
		locks.scope(event.environmentId(), fingerprint);
		var prior = receipts.findById(event.eventId());
		if (prior.isPresent()) {
			var receipt = prior.get();
			if (!receipt.getEnvironmentId().equals(event.environmentId()) || !receipt.getFingerprint().equals(fingerprint)
					|| !receipt.getReceivedAt().equals(received)) throw new AlertException(ErrorCode.INVALID_REQUEST);
			long count = receipt.getIncidentId() == null ? 0 : incidents.findById(receipt.getIncidentId()).orElseThrow().getErrorCount();
			return new Result(Decision.DUPLICATE, receipt.getIncidentId(), count);
		}
		Instant now = now();
		var rule = rules.forEvaluation(event.environmentId(), event.level()).orElse(null);
		boolean enabled = rule != null && rule.isEnabled();
		var receipt = receipts.save(new AlertEventReceipt(event.eventId(), event.environmentId(), fingerprint,
				enabled ? rule.getId() : null, received, now));
		if (!enabled) return new Result(Decision.ACCOUNTED, null, 0);
		Instant latest = receipts.latest(event.environmentId(), fingerprint, rule.getId());
		Instant end = latest == null || received.isAfter(latest) ? received : latest;
		// shortcut: full-window reconstruction is O(n); optimize only with recovery-equivalent evidence.
		var window = receipts.window(event.environmentId(), fingerprint, rule.getId(), end.minusSeconds(rule.getWindowSeconds()), end);
		frequency.reconcile(event.environmentId(), fingerprint, rule.getId(), rule.getWindowSeconds(), window);
		var open = incidents.findByEnvironmentIdAndFingerprintAndStatus(event.environmentId(), fingerprint, IncidentEntity.Status.OPEN);
		if (open.isPresent()) {
			var incident = open.get(); incident.record(received, now); receipt.associate(incident.getId());
			realtime.register(incident, history.save(new IncidentHistory(incident, IncidentHistory.Type.COUNT_UPDATED, now)));
			return new Result(Decision.UPDATED, incident.getId(), incident.getErrorCount());
		}
		var eligible = window.stream().filter(r -> r.getIncidentId() == null).toList();
		if (eligible.size() < rule.getThreshold()) return new Result(Decision.ACCOUNTED, null, 0);
		Instant first = eligible.stream().map(AlertEventReceipt::getReceivedAt).min(Instant::compareTo).orElseThrow();
		var incident = incidents.saveAndFlush(new IncidentEntity(rule.getId(), event.environmentId(), event.level(), fingerprint,
				event.message(), eligible.size(), first, end, now));
		eligible.forEach(r -> r.associate(incident.getId()));
		realtime.register(incident, history.save(new IncidentHistory(incident, IncidentHistory.Type.OPENED, now)));
		return new Result(Decision.OPENED, incident.getId(), incident.getErrorCount());
	}

	@Override
	public int resolveInactive(int limit) {
		if (limit < 1 || limit > 100) throw new AlertException(ErrorCode.INVALID_REQUEST);
		var candidates = Objects.requireNonNull(transactions.execute(tx -> incidents.due(now(), PageRequest.of(0, limit))));
		int resolved = 0;
		for (var candidate : candidates) {
			boolean changed = Boolean.TRUE.equals(transactions.execute(tx -> {
				locks.scope(candidate.getEnvironmentId(), candidate.getFingerprint());
				var rule = rules.forEvaluation(candidate.getEnvironmentId(), candidate.getLevel()).orElseThrow();
				var incident = incidents.findById(candidate.getId()).orElseThrow();
				Instant now = now();
				if (incident.getStatus() != IncidentEntity.Status.OPEN
						|| now.isBefore(incident.getLastSeenAt().plusSeconds(rule.getResolveAfterSeconds()))) return false;
				incident.resolve(now); realtime.register(incident, history.save(new IncidentHistory(incident, IncidentHistory.Type.RESOLVED, now))); return true;
			}));
			if (changed) resolved++;
		}
		return resolved;
	}

	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
