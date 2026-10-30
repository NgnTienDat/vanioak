package com.h.vanioak.modules.alert.internal.engine;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.internal.rule.AlertRuleEntity;
import com.h.vanioak.modules.alert.internal.rule.AlertRuleRepository;

/** Offline transaction/locking model, not a PostgreSQL/Redis substitute certification. */
class EngineTestStore extends AbstractPlatformTransactionManager {
	final Map<UUID, AlertEventReceipt> committedReceipts = new ConcurrentHashMap<>();
	final Map<UUID, IncidentEntity> committedIncidents = new ConcurrentHashMap<>();
	final Map<UUID, IncidentHistory> committedHistory = new ConcurrentHashMap<>();
	final Map<String, Map<UUID, Long>> redisState = new ConcurrentHashMap<>();
	final AlertRuleRepository rules = mock(AlertRuleRepository.class);
	final AlertReceiptRepository receipts = mock(AlertReceiptRepository.class);
	final IncidentRepository incidents = mock(IncidentRepository.class);
	final IncidentHistoryRepository history = mock(IncidentHistoryRepository.class);
	final AlertScopeLock locks = mock(AlertScopeLock.class);
	final RedisAlertFrequency frequency = mock(RedisAlertFrequency.class);
	final MutableClock clock = new MutableClock();
	final UUID env = UUID.randomUUID(), ruleId = UUID.randomUUID();
	final AtomicReference<AlertRuleEntity> rule = new AtomicReference<>();
	final AtomicBoolean failCommit = new AtomicBoolean(), failHistory = new AtomicBoolean();
	volatile Runnable afterRedis = () -> { };
	volatile Runnable afterDue = () -> { };
	private final ThreadLocal<Unit> active = new ThreadLocal<>();
	private final Map<String, ReentrantLock> mutexes = new ConcurrentHashMap<>();
	final AlertEngineService engine;

	EngineTestStore() {
		this(mock(IncidentRealtimePublisher.class));
	}
	EngineTestStore(IncidentRealtimePublisher realtime) {
		configure(2, 60, 300, true);
		when(rules.forEvaluation(any(), any())).thenAnswer(c -> Optional.ofNullable(rule.get())
				.filter(r -> r.getEnvironmentId().equals(c.getArgument(0)) && r.getLevel() == c.getArgument(1)));
		when(receipts.findById(any())).thenAnswer(c -> Optional.ofNullable(receipt(c.getArgument(0))));
		when(receipts.save(any())).thenAnswer(c -> { AlertEventReceipt r = c.getArgument(0); active.get().receipts.put(r.getEventId(), r); return r; });
		when(receipts.latest(any(), any(), any())).thenAnswer(c -> visibleReceipts().stream()
				.filter(r -> matches(r, c.getArgument(0), c.getArgument(1), c.getArgument(2)))
				.map(AlertEventReceipt::getReceivedAt).max(Instant::compareTo).orElse(null));
		when(receipts.window(any(), any(), any(), any(), any())).thenAnswer(c -> visibleReceipts().stream()
				.filter(r -> matches(r, c.getArgument(0), c.getArgument(1), c.getArgument(2)))
				.filter(r -> r.getReceivedAt().isAfter(c.getArgument(3)) && !r.getReceivedAt().isAfter(c.getArgument(4)))
				.map(r -> receipt(r.getEventId())).toList());
		when(incidents.findByEnvironmentIdAndFingerprintAndStatus(any(), any(), any())).thenAnswer(c -> visibleIncidents().stream()
				.filter(i -> i.getEnvironmentId().equals(c.getArgument(0)) && i.getFingerprint().equals(c.getArgument(1)) && i.getStatus() == c.getArgument(2))
				.findFirst().map(i -> incident(i.getId())));
		when(incidents.findById(any())).thenAnswer(c -> Optional.ofNullable(incident(c.getArgument(0))));
		when(incidents.readDetail(any(), anySet())).thenAnswer(c -> Optional.ofNullable(incident(c.getArgument(0)))
				.filter(i -> c.<java.util.Set<UUID>>getArgument(1).contains(i.getEnvironmentId())));
		when(incidents.resolutionScope(any(), anySet())).thenAnswer(c -> Optional.ofNullable(committedIncidents.get(c.getArgument(0)))
				.filter(i -> c.<java.util.Set<UUID>>getArgument(1).contains(i.getEnvironmentId()))
				.map(i -> new IncidentRepository.ResolutionScope() {
					public UUID getEnvironmentId() { return i.getEnvironmentId(); }
					public String getFingerprint() { return i.getFingerprint(); }
				}));
		when(incidents.latestManualResolution(any(), any(), any())).thenAnswer(c -> visibleIncidents().stream()
				.filter(i -> i.getEnvironmentId().equals(c.getArgument(0)) && i.getFingerprint().equals(c.getArgument(1))
						&& i.getResolutionSource() == c.getArgument(2))
				.map(IncidentEntity::getResolvedAt).max(Instant::compareTo).orElse(null));
		when(incidents.saveAndFlush(any())).thenAnswer(c -> {
			IncidentEntity i = c.getArgument(0); ReflectionTestUtils.setField(i, "id", UUID.randomUUID()); active.get().incidents.put(i.getId(), i); return i;
		});
		when(incidents.due(any(), any())).thenAnswer(c -> {
			Instant now = c.getArgument(0); Pageable page = c.getArgument(1);
			var selected = committedIncidents.values().stream().filter(i -> i.getStatus() == IncidentEntity.Status.OPEN)
					.filter(i -> !now.isBefore(i.getLastSeenAt().plusSeconds(rule.get().getResolveAfterSeconds())))
					.sorted(java.util.Comparator.comparing(IncidentEntity::getLastSeenAt)).limit(page.getPageSize()).map(EngineTestStore::copy).toList();
			afterDue.run(); return selected;
		});
		when(history.save(any())).thenAnswer(c -> {
			if (failHistory.getAndSet(false)) throw new IllegalStateException("History persistence failed");
			IncidentHistory h = c.getArgument(0); ReflectionTestUtils.setField(h, "id", UUID.randomUUID()); active.get().history.put(h.getId(), h); return h;
		});
		doAnswer(c -> { lock("event:"+c.getArgument(0)); return null; }).when(locks).event(any());
		doAnswer(c -> { lock("scope:"+c.getArgument(0)+":"+c.getArgument(1)); return null; }).when(locks).scope(any(), any());
		doAnswer(c -> {
			List<AlertEventReceipt> rows = c.getArgument(4); var members = new HashMap<UUID, Long>();
			rows.forEach(r -> members.put(r.getEventId(), RedisAlertFrequency.micros(r.getReceivedAt())));
			redisState.put(c.getArgument(0)+":"+c.getArgument(1), members); afterRedis.run(); return null;
		}).when(frequency).reconcile(any(), any(), any(), anyInt(), any());
		engine = new AlertEngineService(rules, receipts, incidents, history, locks, frequency, this, clock, realtime);
	}

	void configure(int threshold, int window, int resolve, boolean enabled) {
		AlertRuleEntity r = instantiate(AlertRuleEntity.class);
		for (var e : Map.<String, Object>of("id", ruleId, "environmentId", env, "level", Level.ERROR,
				"threshold", threshold, "windowSeconds", window, "resolveAfterSeconds", resolve, "enabled", enabled).entrySet())
			ReflectionTestUtils.setField(r, e.getKey(), e.getValue());
		rule.set(r);
	}

	IncidentEntity onlyIncident() { return committedIncidents.values().iterator().next(); }
	private boolean matches(AlertEventReceipt r, UUID env, String fp, UUID rule) {
		return r.getEnvironmentId().equals(env) && r.getFingerprint().equals(fp) && java.util.Objects.equals(rule, r.getAlertRuleId());
	}
	private List<AlertEventReceipt> visibleReceipts() {
		var all = new HashMap<>(committedReceipts); all.putAll(active.get().receipts); return new ArrayList<>(all.values());
	}
	private List<IncidentEntity> visibleIncidents() {
		var all = new HashMap<>(committedIncidents); all.putAll(active.get().incidents); return new ArrayList<>(all.values());
	}
	private AlertEventReceipt receipt(UUID id) {
		return active.get().receipts.computeIfAbsent(id, key -> copy(committedReceipts.get(key)));
	}
	private IncidentEntity incident(UUID id) {
		return active.get().incidents.computeIfAbsent(id, key -> copy(committedIncidents.get(key)));
	}
	private void lock(String key) {
		var mutex = mutexes.computeIfAbsent(key, k -> new ReentrantLock()); mutex.lock(); active.get().held.add(mutex);
	}
	@Override protected Object doGetTransaction() { return new Object(); }
	@Override protected void doBegin(Object tx, TransactionDefinition definition) {
		if (definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
				|| definition.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED) throw new AssertionError("Unsafe engine boundary");
		active.set(new Unit());
	}
	@Override protected void doCommit(DefaultTransactionStatus status) {
		if (failCommit.getAndSet(false)) throw new TransactionSystemException("Commit failed before durable write");
		var unit = active.get();
		unit.receipts.forEach((k,v) -> committedReceipts.put(k, copy(v)));
		unit.incidents.forEach((k,v) -> committedIncidents.put(k, copy(v)));
		unit.history.forEach((k,v) -> committedHistory.put(k, copy(v)));
	}
	@Override protected void doRollback(DefaultTransactionStatus status) { }
	@Override protected void doCleanupAfterCompletion(Object tx) {
		var unit = active.get(); for (int i=unit.held.size()-1;i>=0;i--) unit.held.get(i).unlock(); active.remove();
	}
	private static class Unit {
		final Map<UUID, AlertEventReceipt> receipts = new HashMap<>();
		final Map<UUID, IncidentEntity> incidents = new HashMap<>();
		final Map<UUID, IncidentHistory> history = new HashMap<>();
		final List<ReentrantLock> held = new ArrayList<>();
	}
	static <T> T instantiate(Class<T> type) {
		try { var c=type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
		catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
	}
	@SuppressWarnings("unchecked") static <T> T copy(T source) {
		if (source==null) return null;
		T result = (T) instantiate(source.getClass());
		try {
			for (var f : source.getClass().getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				f.setAccessible(true); f.set(result, f.get(source));
			}
			return result;
		} catch (IllegalAccessException ex) { throw new AssertionError(ex); }
	}
	static class MutableClock extends Clock {
		final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return time.get(); }
	}
}
