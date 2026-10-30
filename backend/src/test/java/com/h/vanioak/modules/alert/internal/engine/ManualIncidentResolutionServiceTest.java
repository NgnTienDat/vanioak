package com.h.vanioak.modules.alert.internal.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.CriticalEvent;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.Decision;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Scope;
import com.h.vanioak.modules.alert.api.IncidentResolutionFacade.Source;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.EnvironmentScopeFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;

class ManualIncidentResolutionServiceTest {
	final UUID actor = UUID.randomUUID(), app = UUID.randomUUID();
	final ApplicationAccessFacade access = mock(ApplicationAccessFacade.class);
	final EnvironmentScopeFacade scopes = mock(EnvironmentScopeFacade.class);
	final RealtimeFacade realtime = mock(RealtimeFacade.class);
	final IncidentRealtimePublisherTest.ManualExecutor worker = new IncidentRealtimePublisherTest.ManualExecutor();
	final IncidentRealtimePublisher publisher = new IncidentRealtimePublisher(scopes, realtime, worker);
	final EngineTestStore s = new EngineTestStore(publisher);
	final ManualIncidentResolutionService command = new ManualIncidentResolutionService(s.incidents,s.history,s.locks,
			access,scopes,publisher,s,s.clock);
	final Instant base = s.clock.instant();
	ManualIncidentResolutionServiceTest() {
		when(scopes.findApplicationId(s.env)).thenReturn(Optional.of(app));
		when(realtime.publishIncident(any())).thenReturn(new RealtimeFacade.Fanout(true,1,0));
	}
	Scope scope(UUID user) { return new Scope(user,Set.of(s.env),null,null); }
	CriticalEvent event(long seconds) { return new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"Database timeout",base.plusSeconds(seconds)); }
	UUID open() {
		s.configure(1,60,300,true); var id=s.engine.evaluate(event(0)).incidentId(); worker.drain(); clearInvocations(realtime,s.locks);
		return id;
	}
	void close(UUID id, long seconds) { s.clock.time.set(base.plusSeconds(seconds));command.resolve(scope(actor),id).orElseThrow(); }
	@Test void auditIsAtomicAfterCommitAndDuplicateByAnotherActorPreservesWinner() {
		UUID id=open();var original=EngineTestStore.copy(s.onlyIncident());var redis=Map.copyOf(s.redisState);
		s.clock.time.set(base.plusNanos(2_123_456_789L));
		worker.onSubmit=() -> { assertEquals(IncidentEntity.Status.RESOLVED,s.onlyIncident().getStatus());assertEquals(2,s.committedHistory.size()); };
		var result=command.resolve(scope(actor),id).orElseThrow();
		assertEquals(Source.MANUAL,result.resolutionSource());assertEquals(actor,result.resolvedByUserId());
		assertEquals(base.plusNanos(2_123_456_000L),result.resolvedAt());assertEquals(1,worker.pending.size());verifyNoInteractions(realtime);
		var history=s.committedHistory.values().stream().filter(h -> h.getEventType()==IncidentHistory.Type.RESOLVED).findFirst().orElseThrow();
		assertEquals(Map.of("resolution_source","MANUAL","resolved_by_user_id",actor.toString()),history.getMetadata());
		assertEquals(original.getErrorCount(),s.onlyIncident().getErrorCount());assertEquals(original.getFirstSeenAt(),s.onlyIncident().getFirstSeenAt());
		assertEquals(original.getLastSeenAt(),s.onlyIncident().getLastSeenAt());assertEquals(original.getFingerprint(),s.onlyIncident().getFingerprint());
		assertEquals(1,s.committedReceipts.size());assertEquals(redis,s.redisState);
		var other=UUID.randomUUID();assertEquals(result,command.resolve(scope(other),id).orElseThrow());
		verify(access).requireEnvironmentReadAccess(other,app,s.env);assertEquals(2,s.committedHistory.size());assertEquals(1,worker.pending.size());
		worker.drain();var capture=ArgumentCaptor.forClass(RealtimeFacade.IncidentEvent.class);verify(realtime).publishIncident(capture.capture());
		var sent=capture.getValue();assertEquals(history.getId(),sent.eventId());assertEquals(result.resolvedAt(),sent.occurredAt());
		assertEquals("incident.resolved",sent.type());assertEquals(id,sent.incidentId());assertEquals(app,sent.applicationId());assertEquals(s.env,sent.environmentId());
		assertEquals(result.resolvedAt(),sent.resolvedAt());assertNull(sent.errorCount());assertNull(sent.message());
	}
	@Test void publicAuthorizationIsAfterLockAndFreshLoadIncludingDuplicates() {
		UUID id=open();clearInvocations(s.incidents,access);
		command.resolve(scope(actor),id);command.resolve(scope(actor),id);
		var order=inOrder(s.incidents,s.locks,access);
		for(int i=0;i<2;i++) {
			order.verify(s.incidents).resolutionScope(id,Set.of(s.env));order.verify(s.locks).scope(s.env,s.onlyIncident().getFingerprint());
			order.verify(s.incidents).readDetail(id,Set.of(s.env));order.verify(access).requireEnvironmentReadAccess(actor,app,s.env);
		}
		verify(s.incidents,never()).findById(any());
	}
	@Test void deniedRevokedAssignmentOrIdentityFailureCannotMutate() {
		UUID id=open();
		for(RuntimeException failure:List.of(new IdentityException(ErrorCode.ACCESS_DENIED),new IllegalStateException("identity unavailable"))) {
			doThrow(failure).when(access).requireEnvironmentReadAccess(actor,app,s.env);
			assertSame(failure,assertThrows(RuntimeException.class,() -> command.resolve(scope(actor),id)));
			assertEquals(IncidentEntity.Status.OPEN,s.onlyIncident().getStatus());assertEquals(1,s.committedHistory.size());assertTrue(worker.pending.isEmpty());
		}
	}
	@Test void emptyForeignOrMissingScopeReturnsEmptyWithoutMutation() {
		UUID id=open();assertTrue(command.resolve(new Scope(actor,Set.of(),null,null),id).isEmpty());
		assertTrue(command.resolve(new Scope(actor,Set.of(UUID.randomUUID()),null,null),id).isEmpty());
		assertTrue(command.resolve(scope(actor),UUID.randomUUID()).isEmpty());verifyNoInteractions(access);assertEquals(1,s.committedHistory.size());
	}
	@Test void missingParentMetadataFailsClosed() {
		UUID id=open();when(scopes.findApplicationId(s.env)).thenReturn(Optional.empty());
		assertThrows(com.h.vanioak.modules.alert.api.AlertException.class,() -> command.resolve(scope(actor),id));
		assertEquals(IncidentEntity.Status.OPEN,s.onlyIncident().getStatus());verifyNoInteractions(access);assertTrue(worker.pending.isEmpty());
	}
	@Test void historyAndCommitFailureRollbackWithoutPublication() {
		UUID id=open();s.failHistory.set(true);assertThrows(IllegalStateException.class,() -> command.resolve(scope(actor),id));
		s.failCommit.set(true);assertThrows(TransactionSystemException.class,() -> command.resolve(scope(actor),id));
		assertEquals(IncidentEntity.Status.OPEN,s.onlyIncident().getStatus());assertNull(s.onlyIncident().getResolutionSource());
		assertNull(s.onlyIncident().getResolvedAt());assertEquals(1,s.committedHistory.size());assertTrue(worker.pending.isEmpty());verifyNoInteractions(realtime);
	}
	@Test void unavailableRealtimeCannotRollbackCommittedManualResolution() {
		UUID id=open();when(realtime.publishIncident(any())).thenReturn(new RealtimeFacade.Fanout(false,0,0));
		command.resolve(scope(actor),id);worker.drain();assertEquals(Source.MANUAL,s.onlyIncident().getResolutionSource());assertEquals(2,s.committedHistory.size());
	}
	@Test void automaticWinnerAndLegacyNullAuditAreNeverOverwritten() {
		UUID id=open();s.clock.time.set(base.plusSeconds(300));assertEquals(1,s.engine.resolveInactive(100));worker.drain();
		var auto=command.resolve(scope(actor),id).orElseThrow();assertEquals(Source.AUTOMATIC,auto.resolutionSource());assertNull(auto.resolvedByUserId());assertTrue(worker.pending.isEmpty());
		var h=s.committedHistory.values().stream().filter(x -> x.getEventType()==IncidentHistory.Type.RESOLVED).findFirst().orElseThrow();
		assertEquals(Map.of("resolution_source","AUTOMATIC"),h.getMetadata());
		ReflectionTestUtils.setField(s.onlyIncident(),"resolutionSource",null);var legacy=command.resolve(scope(actor),id).orElseThrow();
		assertNull(legacy.resolutionSource());assertNull(legacy.resolvedByUserId());assertEquals(auto.resolvedAt(),legacy.resolvedAt());assertEquals(2,s.committedHistory.size());
	}
	@Test void manualWinnerCannotBeAutoResolvedAgainOrCloseANewerIncident() {
		UUID id=open();close(id,1);s.clock.time.set(base.plusSeconds(301));assertEquals(0,s.engine.resolveInactive(100));
		var fresh=s.engine.evaluate(event(301)).incidentId();assertNotEquals(id,fresh);command.resolve(scope(actor),id);
		assertEquals(IncidentEntity.Status.OPEN,s.committedIncidents.get(fresh).getStatus());assertEquals(3,s.committedHistory.size());
	}
	@Test void staleEqualityAndDelayedDeliveriesCannotOpenOrIncreaseNewOpenIncident() {
		UUID id=open();close(id,10);s.configure(2,60,300,true);
		for(long seconds:new long[]{-1,0,9,10}) assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(seconds)).decision());
		assertEquals(1,s.committedIncidents.size());assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(11)).decision());
		var created=s.engine.evaluate(event(12));assertEquals(Decision.OPENED,created.decision());assertNotEquals(id,created.incidentId());assertEquals(2,created.errorCount());
		var before=EngineTestStore.copy(s.committedIncidents.get(created.incidentId()));var history=s.committedHistory.size();
		var stale=event(10);assertEquals(Decision.ACCOUNTED,s.engine.evaluate(stale).decision());assertEquals(Decision.DUPLICATE,s.engine.evaluate(stale).decision());
		assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(8)).decision());
		var current=s.committedIncidents.get(created.incidentId());assertEquals(before.getErrorCount(),current.getErrorCount());
		assertEquals(before.getFirstSeenAt(),current.getFirstSeenAt());assertEquals(before.getLastSeenAt(),current.getLastSeenAt());
		assertEquals(before.getUpdatedAt(),current.getUpdatedAt());assertEquals(history,s.committedHistory.size());
		assertEquals(9,s.committedReceipts.size());assertNull(s.committedReceipts.get(stale.eventId()).getIncidentId());
		assertEquals(9,s.redisState.values().iterator().next().size());
	}
	@Test void preCloseUnassignedReceiptsAreExcludedAndLatestManualCutoffWins() {
		s.configure(2,60,300,true);var old=event(-100);s.engine.evaluate(old);
		UUID first=open();s.configure(3,300,300,true);close(first,10);
		assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(11)).decision());assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(12)).decision());
		var second=s.engine.evaluate(event(13)).incidentId();assertNotNull(second);assertNull(s.committedReceipts.get(old.eventId()).getIncidentId());close(second,20);
		assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(20)).decision());assertEquals(Decision.ACCOUNTED,s.engine.evaluate(event(15)).decision());
		assertEquals(2,s.committedIncidents.size());assertEquals(4,s.committedHistory.values().stream().filter(h -> h.getEventType()!=IncidentHistory.Type.COUNT_UPDATED).count());
	}
	@Test void concurrentManualCallsCreateExactlyOneTransitionAndPreserveWinningActor() throws Exception {
		UUID id=open(),other=UUID.randomUUID();var start=new CountDownLatch(1);
		try(var pool=Executors.newFixedThreadPool(2)) {
			var a=pool.submit(() -> {assertTrue(start.await(5,TimeUnit.SECONDS));return command.resolve(scope(actor),id).orElseThrow();});
			var b=pool.submit(() -> {assertTrue(start.await(5,TimeUnit.SECONDS));return command.resolve(scope(other),id).orElseThrow();});
			start.countDown();assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
		}
		assertEquals(2,s.committedHistory.size());assertEquals(1,worker.pending.size());assertTrue(Set.of(actor,other).contains(s.onlyIncident().getResolvedByUserId()));
	}
	@Test void manualAndAutomaticRaceShareScopeLockAndCreateOneResolution() throws Exception {
		UUID id=open();s.clock.time.set(base.plusSeconds(300));var held=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(c -> {held.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(access).requireEnvironmentReadAccess(actor,app,s.env);
		try(var pool=Executors.newFixedThreadPool(2)) {
			var manual=pool.submit(() -> command.resolve(scope(actor),id));assertTrue(held.await(5,TimeUnit.SECONDS));
			var auto=pool.submit(() -> s.engine.resolveInactive(100));release.countDown();manual.get(5,TimeUnit.SECONDS);assertEquals(0,auto.get(5,TimeUnit.SECONDS));
		} finally {release.countDown();}
		assertEquals(Source.MANUAL,s.onlyIncident().getResolutionSource());assertEquals(2,s.committedHistory.size());assertEquals(1,worker.pending.size());
	}
	@Test void eventWaitingBehindManualLockObservesCutoffAndCannotUpdate() throws Exception {
		UUID id=open();s.clock.time.set(base.plusSeconds(10));var held=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(c -> {held.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(access).requireEnvironmentReadAccess(actor,app,s.env);
		try(var pool=Executors.newFixedThreadPool(2)) {
			var manual=pool.submit(() -> command.resolve(scope(actor),id));assertTrue(held.await(5,TimeUnit.SECONDS));
			var late=pool.submit(() -> s.engine.evaluate(event(10)));release.countDown();manual.get(5,TimeUnit.SECONDS);assertEquals(Decision.ACCOUNTED,late.get(5,TimeUnit.SECONDS).decision());
		} finally {release.countDown();}
		assertEquals(1,s.onlyIncident().getErrorCount());assertEquals(2,s.committedReceipts.size());assertEquals(2,s.committedHistory.size());
	}
	@Test void eventHoldingScopeLockCommitsUpdateBeforeManualFreshRead() throws Exception {
		UUID id=open();var held=new CountDownLatch(1);var release=new CountDownLatch(1);s.clock.time.set(base.plusSeconds(10));
		s.afterRedis=() -> {held.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
		try(var pool=Executors.newFixedThreadPool(2)) {
			var update=pool.submit(() -> s.engine.evaluate(event(5)));assertTrue(held.await(5,TimeUnit.SECONDS));
			var manual=pool.submit(() -> command.resolve(scope(actor),id));release.countDown();assertEquals(Decision.UPDATED,update.get(5,TimeUnit.SECONDS).decision());manual.get(5,TimeUnit.SECONDS);
		} finally {release.countDown();}
		assertEquals(2,s.onlyIncident().getErrorCount());assertEquals(IncidentEntity.Status.RESOLVED,s.onlyIncident().getStatus());assertEquals(3,s.committedHistory.size());
	}
}
