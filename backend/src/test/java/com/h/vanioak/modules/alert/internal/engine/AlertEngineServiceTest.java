package com.h.vanioak.modules.alert.internal.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.CriticalEvent;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.Decision;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

class AlertEngineServiceTest {
	@Test void automaticOnlyResolutionDoesNotIntroduceManualCutoffForLateEvents() {
		s.configure(1,60,5,true);var first=s.engine.evaluate(event(0)).incidentId();
		s.clock.time.set(base.plusSeconds(5));assertEquals(1,s.engine.resolveInactive(100));
		assertEquals(com.h.vanioak.modules.alert.api.IncidentResolutionFacade.Source.AUTOMATIC,s.committedIncidents.get(first).getResolutionSource());
		assertNull(s.committedIncidents.get(first).getResolvedByUserId());
		var fresh=s.engine.evaluate(event(6));assertNotEquals(first,fresh.incidentId());
		assertEquals(Decision.UPDATED,s.engine.evaluate(event(4)).decision());
		assertEquals(2,s.committedIncidents.get(fresh.incidentId()).getErrorCount());
	}
	final EngineTestStore s = new EngineTestStore();
	final Instant base = s.clock.instant();
	CriticalEvent event(long seconds) { return new CriticalEvent(UUID.randomUUID(), s.env, Level.ERROR, "Database timeout", base.plusSeconds(seconds)); }
	void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
	}

	@Test void thresholdCountsDistinctEventsAndAssociatesOpeningWindow() {
		var first=event(0); var second=event(1);
		assertEquals(Decision.ACCOUNTED,s.engine.evaluate(first).decision()); assertTrue(s.committedIncidents.isEmpty());
		var result=s.engine.evaluate(second); assertEquals(Decision.OPENED,result.decision()); assertEquals(2,result.errorCount());
		assertEquals(base,s.onlyIncident().getFirstSeenAt()); assertEquals(base.plusSeconds(1),s.onlyIncident().getLastSeenAt());
		assertTrue(s.committedReceipts.values().stream().allMatch(r -> result.incidentId().equals(r.getIncidentId())));
		assertEquals(1,s.committedHistory.size()); assertEquals(IncidentHistory.Type.OPENED,s.committedHistory.values().iterator().next().getEventType());
	}

	@Test void equalReceivedTimesAreDistinctMembers() {
		s.configure(3,60,300,true);
		s.engine.evaluate(event(0)); s.engine.evaluate(event(0)); assertEquals(3,s.engine.evaluate(event(0)).errorCount());
		assertEquals(3,s.committedReceipts.size()); assertEquals(3,s.redisState.values().iterator().next().size());
	}

	@Test void differentMessagesAndEnvironmentsHaveSeparateIncidents() {
		s.configure(1,60,300,true); s.engine.evaluate(event(0));
		s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"other message",base));
		UUID otherEnv=UUID.randomUUID(); var otherRule=EngineTestStore.copy(s.rule.get());
		org.springframework.test.util.ReflectionTestUtils.setField(otherRule,"environmentId",otherEnv);
		org.springframework.test.util.ReflectionTestUtils.setField(otherRule,"id",UUID.randomUUID());
		when(s.rules.forEvaluation(otherEnv,Level.ERROR)).thenReturn(java.util.Optional.of(otherRule));
		s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),otherEnv,Level.ERROR,"Database timeout",base));
		assertEquals(3,s.committedIncidents.size()); assertTrue(s.committedIncidents.values().stream().allMatch(i -> i.getErrorCount()==1));
	}

	@Test void errorAndCriticalDoNotShareFrequencyOrIncident() {
		s.configure(1,60,300,true); s.engine.evaluate(event(0)); var criticalRule=EngineTestStore.copy(s.rule.get());
		org.springframework.test.util.ReflectionTestUtils.setField(criticalRule,"level",Level.CRITICAL);
		org.springframework.test.util.ReflectionTestUtils.setField(criticalRule,"id",UUID.randomUUID());
		when(s.rules.forEvaluation(s.env,Level.CRITICAL)).thenReturn(java.util.Optional.of(criticalRule));
		s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.CRITICAL,"Database timeout",base));
		assertEquals(2,s.committedIncidents.size()); assertEquals(2,s.committedIncidents.values().stream().map(IncidentEntity::getFingerprint).distinct().count());
	}

	@Test void lowerWindowBoundaryIsExclusiveAndUpperInclusive() {
		var excluded=event(0); s.engine.evaluate(excluded); s.engine.evaluate(event(60));
		assertTrue(s.committedIncidents.isEmpty()); var opened=s.engine.evaluate(event(60)); assertEquals(2,opened.errorCount());
		assertNull(s.committedReceipts.get(excluded.eventId()).getIncidentId());
		assertEquals(base.plusSeconds(60),s.onlyIncident().getFirstSeenAt());
	}

	@Test void duplicatesDoNotAdvanceClockWindowOrModifyHistoryAndDoNotNeedRedis() {
		var first=event(0); s.engine.evaluate(first); clearInvocations(s.frequency,s.rules);
		s.clock.time.set(base.plusSeconds(10000)); assertEquals(Decision.DUPLICATE,s.engine.evaluate(first).decision());
		verifyNoInteractions(s.frequency,s.rules); assertEquals(1,s.committedReceipts.size());
		assertTrue(s.committedHistory.isEmpty()); assertTrue(s.committedIncidents.isEmpty());
	}

	@Test void committedOpeningEventsAreNotCountedAgainOnRedelivery() {
		var first=event(0); s.engine.evaluate(first); var second=event(1); s.engine.evaluate(second);
		assertEquals(Decision.DUPLICATE,s.engine.evaluate(first).decision()); s.engine.evaluate(second);
		assertEquals(2,s.onlyIncident().getErrorCount()); assertEquals(1,s.committedHistory.size());
	}

	@Test void conflictingDuplicateIdentityFailsWithoutChangingState() {
		var first=event(0); s.engine.evaluate(first);
		assertThrows(AlertException.class,() -> s.engine.evaluate(new CriticalEvent(first.eventId(),s.env,Level.ERROR,"different",first.receivedAt())));
		assertEquals(1,s.committedReceipts.size()); assertTrue(s.committedIncidents.isEmpty());
	}

	@Test void lateOutsideWindowDoesNotHelpOpeningButCountsOnceWhileOpen() {
		s.engine.evaluate(event(120)); s.engine.evaluate(event(0)); assertTrue(s.committedIncidents.isEmpty());
		s.engine.evaluate(event(121)); var late=event(-60); var updated=s.engine.evaluate(late);
		assertEquals(Decision.UPDATED,updated.decision()); assertEquals(3,updated.errorCount());
		assertEquals(base.minusSeconds(60),s.onlyIncident().getFirstSeenAt());
		assertEquals(base.plusSeconds(121),s.onlyIncident().getLastSeenAt());
		s.engine.evaluate(late); assertEquals(3,s.onlyIncident().getErrorCount());
	}

	@Test void missingAndDisabledRuleReceiptsNeverEnterFrequencyLater() {
		s.rule.set(null); var missing=event(0); s.engine.evaluate(missing);
		s.configure(2,60,300,false); var disabled=event(1); s.engine.evaluate(disabled);
		verifyNoInteractions(s.frequency); assertTrue(s.committedReceipts.values().stream().allMatch(r -> r.getAlertRuleId()==null));
		s.configure(2,60,300,true); s.engine.evaluate(event(2)); assertTrue(s.committedIncidents.isEmpty());
		s.engine.evaluate(event(3)); s.engine.evaluate(missing); s.engine.evaluate(disabled); assertEquals(2,s.onlyIncident().getErrorCount());
	}

	@Test void configurationChangesApplyOnNextEvaluationWithoutRecountingOpenIncident() {
		s.configure(3,60,300,true); s.engine.evaluate(event(0)); s.engine.evaluate(event(1));
		s.configure(2,60,300,true); assertTrue(s.committedIncidents.isEmpty());
		assertEquals(3,s.engine.evaluate(event(2)).errorCount());
		s.configure(100,1,300,true); assertEquals(4,s.engine.evaluate(event(100)).errorCount());
		assertEquals(base,s.onlyIncident().getFirstSeenAt());
		assertEquals(List.of(3L,4L),s.committedHistory.values().stream().map(IncidentHistory::getErrorCount).sorted().toList());
	}

	@Test void disabledRuleDoesNotUpdateOpenIncidentButResolverStillUsesItsDuration() {
		s.configure(1,60,5,true); s.engine.evaluate(event(0));
		s.configure(1,60,5,false); s.engine.evaluate(event(1)); assertEquals(1,s.onlyIncident().getErrorCount());
		s.clock.time.set(base.plusSeconds(5)); assertEquals(1,s.engine.resolveInactive(100));
	}

	@Test void resolutionIsInclusiveIdempotentAndUsesCurrentConfiguration() {
		s.configure(1,60,300,true); s.engine.evaluate(event(0)); s.configure(1,60,5,true);
		s.clock.time.set(base.plusSeconds(5).minusNanos(1000)); assertEquals(0,s.engine.resolveInactive(100));
		s.clock.time.set(base.plusSeconds(5)); assertEquals(1,s.engine.resolveInactive(100)); assertEquals(0,s.engine.resolveInactive(100));
		assertEquals(IncidentEntity.Status.RESOLVED,s.onlyIncident().getStatus()); assertEquals(base.plusSeconds(5),s.onlyIncident().getResolvedAt());
		assertEquals(2,s.committedHistory.size());
	}

	@Test void resolutionIsBoundedAndHistoryFailureRollsBackStatus() {
		s.configure(1,60,5,true); s.engine.evaluate(event(0));
		s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"second group",base));
		s.clock.time.set(base.plusSeconds(5)); s.failHistory.set(true);
		assertThrows(IllegalStateException.class,() -> s.engine.resolveInactive(1));
		assertTrue(s.committedIncidents.values().stream().allMatch(i -> i.getStatus()==IncidentEntity.Status.OPEN));
		assertEquals(2,s.committedHistory.size()); assertEquals(1,s.engine.resolveInactive(1));
		assertEquals(1,s.engine.resolveInactive(1)); assertEquals(0,s.engine.resolveInactive(1)); assertEquals(4,s.committedHistory.size());
	}

	@Test void newIncidentExcludesEventsAlreadyAssignedToResolvedIncident() {
		s.configure(2,600,5,true); var original=event(0); s.engine.evaluate(original); var old=s.engine.evaluate(event(1)).incidentId();
		s.clock.time.set(base.plusSeconds(6)); s.engine.resolveInactive(100); s.engine.evaluate(event(6));
		assertEquals(1,s.committedIncidents.size()); var next=s.engine.evaluate(event(7));
		assertEquals(Decision.OPENED,next.decision()); assertNotEquals(old,next.incidentId()); assertEquals(2,next.errorCount());
		s.engine.evaluate(original); assertEquals(2,s.committedIncidents.get(next.incidentId()).getErrorCount());
	}

	@Test void redisLossIsRebuiltFromDurableReceipts() {
		s.engine.evaluate(event(0)); s.redisState.clear(); assertEquals(2,s.engine.evaluate(event(1)).errorCount());
		assertEquals(2,s.redisState.values().iterator().next().size());
	}

	@Test void rollbackAfterRedisMutationRemovesTentativeMemberOnReconciliation() {
		var first=event(0); var failed=event(1); s.engine.evaluate(first);
		var once=new AtomicBoolean(true); s.afterRedis=() -> { if(once.getAndSet(false))throw new IllegalStateException("Redis uncertain"); };
		assertThrows(IllegalStateException.class,() -> s.engine.evaluate(failed)); assertEquals(1,s.committedReceipts.size());
		assertEquals(2,s.redisState.values().iterator().next().size());
		var third=event(2); assertEquals(2,s.engine.evaluate(third).errorCount());
		assertFalse(s.redisState.values().iterator().next().containsKey(failed.eventId()));
		assertEquals(3,s.engine.evaluate(failed).errorCount()); assertEquals(3,s.committedReceipts.size());
	}

	@Test void failureAfterIncidentMutationRollsBackReceiptCountAndHistory() {
		s.configure(1,60,300,true); s.engine.evaluate(event(0)); var failed=event(1); s.failHistory.set(true);
		assertThrows(IllegalStateException.class,() -> s.engine.evaluate(failed));
		assertEquals(1,s.onlyIncident().getErrorCount()); assertEquals(1,s.committedReceipts.size()); assertEquals(1,s.committedHistory.size());
		assertEquals(2,s.engine.evaluate(failed).errorCount());
	}

	@Test void commitFailureNeverReturnsSuccessfulAccounting() {
		s.configure(1,60,300,true); var failed=event(0); s.failCommit.set(true);
		assertThrows(TransactionSystemException.class,() -> s.engine.evaluate(failed));
		assertTrue(s.committedReceipts.isEmpty()); assertTrue(s.committedIncidents.isEmpty()); assertTrue(s.committedHistory.isEmpty());
		assertEquals(Decision.OPENED,s.engine.evaluate(failed).decision());
	}

	@Test void concurrentThresholdCrossingsCreateOneOpenIncident() throws Exception {
		s.configure(1,60,300,true); var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
		try {
			var futures=List.of(event(0),event(0)).stream().map(e -> pool.submit(() -> { ready.countDown(); await(go); return s.engine.evaluate(e); })).toList();
			await(ready); go.countDown(); for(var future:futures)future.get(5,TimeUnit.SECONDS);
			assertEquals(1,s.committedIncidents.size()); assertEquals(2,s.onlyIncident().getErrorCount());
			assertEquals(2,s.committedReceipts.size()); assertEquals(2,s.committedHistory.size());
		} finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
	}

	@Test void concurrentDuplicateHasOneReceiptAndOneLifecycleChange() throws Exception {
		s.configure(1,60,300,true); var event=event(0); var pool=Executors.newFixedThreadPool(2); var go=new CountDownLatch(1);
		try {
			var first=pool.submit(() -> { await(go); return s.engine.evaluate(event); });
			var second=pool.submit(() -> { await(go); return s.engine.evaluate(event); }); go.countDown();
			assertEquals(java.util.Set.of(Decision.OPENED,Decision.DUPLICATE),java.util.Set.of(first.get(5,TimeUnit.SECONDS).decision(),second.get(5,TimeUnit.SECONDS).decision()));
			assertEquals(1,s.onlyIncident().getErrorCount()); assertEquals(1,s.committedReceipts.size()); assertEquals(1,s.committedHistory.size());
		} finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
	}

	@Test void resolverRechecksAfterConcurrentEventUpdatesLastSeen() throws Exception {
		s.configure(1,60,5,true); s.engine.evaluate(event(0)); s.clock.time.set(base.plusSeconds(5));
		var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var selected=new CountDownLatch(1);
		s.afterRedis=() -> { entered.countDown(); await(release); }; s.afterDue=selected::countDown;
		var pool=Executors.newFixedThreadPool(2);
		try {
			var update=pool.submit(() -> s.engine.evaluate(event(5))); await(entered);
			var resolve=pool.submit(() -> s.engine.resolveInactive(100)); await(selected); release.countDown();
			assertEquals(2,update.get(5,TimeUnit.SECONDS).errorCount()); assertEquals(0,resolve.get(5,TimeUnit.SECONDS));
			assertEquals(IncidentEntity.Status.OPEN,s.onlyIncident().getStatus());
		} finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
	}

	@Test void microsecondPrecisionIsStableAcrossRedelivery() {
		var e=new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"message",base.plusNanos(1999)); s.engine.evaluate(e);
		assertEquals(base.plusNanos(1000),s.committedReceipts.get(e.eventId()).getReceivedAt());
		assertEquals(Decision.DUPLICATE,s.engine.evaluate(e).decision());
		assertEquals(e.receivedAt().truncatedTo(ChronoUnit.MICROS),s.committedReceipts.get(e.eventId()).getReceivedAt());
	}

	@Test void invalidInputAndBatchLimitsNeverStartAccounting() {
		assertThrows(AlertException.class,() -> s.engine.evaluate(null));
		assertThrows(AlertException.class,() -> s.engine.evaluate(new CriticalEvent(null,s.env,Level.ERROR,"m",base)));
		assertThrows(AlertException.class,() -> s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"m",Instant.MAX)));
		assertThrows(AlertException.class,() -> s.engine.resolveInactive(0)); assertThrows(AlertException.class,() -> s.engine.resolveInactive(101));
		assertTrue(s.committedReceipts.isEmpty()); verifyNoInteractions(s.locks,s.frequency);
	}
}
