package com.h.vanioak.modules.alert.internal.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.CriticalEvent;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.Decision;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.internal.consumer.AlertEventDecoder;
import com.h.vanioak.modules.alert.internal.consumer.AlertFailurePublisher;
import com.h.vanioak.modules.alert.internal.consumer.AlertLogConsumer;
import com.h.vanioak.modules.identity.api.EnvironmentScopeFacade;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import com.rabbitmq.client.Channel;
import tools.jackson.databind.ObjectMapper;

class IncidentRealtimePublisherTest {
	final EnvironmentScopeFacade scopes=mock(EnvironmentScopeFacade.class);
	final RealtimeFacade realtime=mock(RealtimeFacade.class);
	final UUID app=UUID.randomUUID();
	final ManualExecutor worker=new ManualExecutor();
	final IncidentRealtimePublisher publisher=new IncidentRealtimePublisher(scopes,realtime,worker);
	final EngineTestStore store=new EngineTestStore(publisher);
	IncidentRealtimePublisherTest() {
		when(scopes.findApplicationId(store.env)).thenReturn(Optional.of(app));
		when(realtime.publishIncident(any())).thenReturn(new RealtimeFacade.Fanout(true,0,0));
	}
	CriticalEvent event() { return new CriticalEvent(UUID.randomUUID(),store.env,Level.ERROR,"Database timeout",store.clock.instant()); }
	@Test void threeCommittedTransitionsUseFrozenHistoryIdentityFieldsAndAuthoritativeScope() {
		store.configure(1,60,300,true);worker.onSubmit=() -> assertFalse(store.committedHistory.isEmpty());
		var first=event();assertEquals(Decision.OPENED,store.engine.evaluate(first).decision());
		store.clock.time.set(store.clock.instant().plusSeconds(1));assertEquals(Decision.UPDATED,store.engine.evaluate(event()).decision());
		Instant last=store.clock.instant();store.clock.time.set(last.plusSeconds(300));Instant resolved=store.clock.instant();
		assertEquals(1,store.engine.resolveInactive(100));assertEquals(3,worker.pending.size());verifyNoInteractions(scopes,realtime);
		ReflectionTestUtils.setField(store.onlyIncident(),"errorCount",999L);store.clock.time.set(resolved.plusSeconds(100));worker.drain();
		var capture=ArgumentCaptor.forClass(RealtimeFacade.IncidentEvent.class);verify(realtime,times(3)).publishIncident(capture.capture());var events=capture.getAllValues();
		assertEquals(List.of("incident.created","incident.updated","incident.resolved"),events.stream().map(RealtimeFacade.IncidentEvent::type).toList());
		assertEquals(3,events.stream().map(RealtimeFacade.IncidentEvent::eventId).distinct().count());
		for(var e:events) {
			assertEquals(app,e.applicationId());assertEquals(store.env,e.environmentId());assertEquals("ERROR",e.level());assertEquals("alert",e.producer());assertEquals(1,e.schemaVersion());
			assertEquals(store.onlyIncident().getId(),e.incidentId());assertNotEquals(first.eventId(),e.eventId());
			assertEquals(store.committedHistory.get(e.eventId()).getEventAt(),e.occurredAt());
		}
		assertEquals("OPEN",events.get(0).status());assertEquals("Database timeout",events.get(0).message());assertEquals(Long.valueOf(1),events.get(0).errorCount());
		assertEquals(Long.valueOf(2),events.get(1).errorCount());assertEquals(last,events.get(1).lastSeenAt());assertNull(events.get(1).message());
		assertEquals("RESOLVED",events.get(2).status());assertEquals(resolved,events.get(2).resolvedAt());assertNull(events.get(2).errorCount());
	}
	@Test void committedDuplicatesNeverReemitEvenAfterResolution() {
		store.configure(1,60,300,true);var first=event();store.engine.evaluate(first);worker.drain();
		assertEquals(Decision.DUPLICATE,store.engine.evaluate(first).decision());assertTrue(worker.pending.isEmpty());
		store.clock.time.set(store.clock.instant().plusSeconds(300));assertEquals(1,store.engine.resolveInactive(100));worker.drain();
		assertEquals(0,store.engine.resolveInactive(100));assertEquals(Decision.DUPLICATE,store.engine.evaluate(first).decision());assertTrue(worker.pending.isEmpty());
		verify(realtime,times(2)).publishIncident(any());assertEquals(1,store.onlyIncident().getErrorCount());
	}
	@Test void receiptOnlyBelowThresholdDisabledAndMissingRulesNeverQueueEvents() {
		store.configure(3,60,300,true);assertEquals(Decision.ACCOUNTED,store.engine.evaluate(event()).decision());
		store.configure(1,60,300,false);assertEquals(Decision.ACCOUNTED,store.engine.evaluate(event()).decision());
		store.rule.set(null);assertEquals(Decision.ACCOUNTED,store.engine.evaluate(event()).decision());
		assertEquals(3,store.committedReceipts.size());assertTrue(worker.pending.isEmpty());verifyNoInteractions(scopes,realtime);
	}
	@Test void historyRollbackAndCommitFailureNeverEnqueueOrPublish() {
		store.configure(1,60,300,true);store.failHistory.set(true);assertThrows(IllegalStateException.class,() -> store.engine.evaluate(event()));
		store.failCommit.set(true);assertThrows(TransactionSystemException.class,() -> store.engine.evaluate(event()));
		assertTrue(worker.pending.isEmpty());assertTrue(store.committedIncidents.isEmpty());assertTrue(store.committedReceipts.isEmpty());verifyNoInteractions(scopes,realtime);
	}
	@Test void resolutionRollbackDoesNotPublishOrChangeCommittedOpenState() {
		store.configure(1,60,300,true);store.engine.evaluate(event());worker.drain();store.clock.time.set(store.clock.instant().plusSeconds(300));store.failHistory.set(true);
		assertThrows(IllegalStateException.class,() -> store.engine.resolveInactive(100));assertEquals(IncidentEntity.Status.OPEN,store.onlyIncident().getStatus());
		assertTrue(worker.pending.isEmpty());verify(realtime,times(1)).publishIncident(any());
	}
	@Test void reopenHasNewIncidentAndHistoryEventIdentities() {
		store.configure(1,60,300,true);var first=store.engine.evaluate(event()).incidentId();store.clock.time.set(store.clock.instant().plusSeconds(300));store.engine.resolveInactive(100);
		var second=store.engine.evaluate(event()).incidentId();assertNotEquals(first,second);worker.drain();
		var capture=ArgumentCaptor.forClass(RealtimeFacade.IncidentEvent.class);verify(realtime,times(3)).publishIncident(capture.capture());
		assertEquals(second,capture.getAllValues().getLast().incidentId());assertEquals("incident.created",capture.getAllValues().getLast().type());
	}
	@Test void lookupFailureAndMissingMetadataCannotAlterAccountingOrForgeScope() {
		for(boolean missing:List.of(false,true)) {
			var executor=new ManualExecutor();var lookup=mock(EnvironmentScopeFacade.class);var sink=mock(RealtimeFacade.class);
			if(missing)when(lookup.findApplicationId(any())).thenReturn(Optional.empty());else when(lookup.findApplicationId(any())).thenThrow(new IllegalStateException("offline"));
			try(var bridge=new IncidentRealtimePublisher(lookup,sink,executor)) {
				var s=new EngineTestStore(bridge);s.configure(1,60,300,true);var result=s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"message",s.clock.instant()));
				executor.drain();assertEquals(Decision.OPENED,result.decision());assertEquals(1,s.committedReceipts.size());assertEquals(1,s.onlyIncident().getErrorCount());verifyNoInteractions(sink);
			}
		}
	}
	@Test void throwingOrUnavailableRealtimeAndZeroSubscribersDoNotChangeCommittedResults() {
		for(int outcome=0;outcome<3;outcome++) {
			var executor=new ManualExecutor();var sink=mock(RealtimeFacade.class);
			if(outcome==0)when(sink.publishIncident(any())).thenThrow(new IllegalStateException("unavailable"));
			else when(sink.publishIncident(any())).thenReturn(new RealtimeFacade.Fanout(outcome==2,0,0));
			try(var bridge=new IncidentRealtimePublisher(scopes,sink,executor)) {
				var s=new EngineTestStore(bridge);when(scopes.findApplicationId(s.env)).thenReturn(Optional.of(app));s.configure(1,60,300,true);
				assertEquals(Decision.OPENED,s.engine.evaluate(new CriticalEvent(UUID.randomUUID(),s.env,Level.ERROR,"message",s.clock.instant())).decision());executor.drain();
				s.clock.time.set(s.clock.instant().plusSeconds(300));assertEquals(1,s.engine.resolveInactive(100));executor.drain();
				assertEquals(IncidentEntity.Status.RESOLVED,s.onlyIncident().getStatus());assertEquals(1,s.committedReceipts.size());verify(sink,times(2)).publishIncident(any());
			}
		}
	}
	@Test void registrationOutsideTransactionNeverFallsBackToImmediatePublication() {
		store.configure(1,60,300,true);store.engine.evaluate(event());worker.pending.clear();assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
		publisher.register(store.onlyIncident(),store.committedHistory.values().iterator().next());assertTrue(worker.pending.isEmpty());verifyNoInteractions(scopes,realtime);
	}
	@Test void shutdownDiscardsPendingAndFutureNotificationsWithoutChangingPersistence() {
		store.configure(1,60,300,true);store.engine.evaluate(event());publisher.close();publisher.close();
		assertTrue(worker.pending.isEmpty());assertEquals(Decision.UPDATED,store.engine.evaluate(event()).decision());assertEquals(2,store.onlyIncident().getErrorCount());
		assertEquals(5,worker.waitSeconds);assertEquals(1,worker.shutdownCalls);verifyNoInteractions(scopes,realtime);
	}
	@Test void saturationAndBlockedWorkerNeverPreventCommittedConsumerAckOrCreateReplacement() throws Exception {
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var workerThread=new AtomicReference<Thread>();
		when(scopes.findApplicationId(any())).thenAnswer(call -> {workerThread.set(Thread.currentThread());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Optional.of(app);});
		var bridge=new IncidentRealtimePublisher(scopes,realtime);var s=new EngineTestStore(bridge);s.configure(1,60,300,true);
		var channel=mock(Channel.class);when(channel.isOpen()).thenReturn(true);var failures=mock(AlertFailurePublisher.class);var stop=mock(Runnable.class);
		var consumer=new AlertLogConsumer(s.engine,new AlertEventDecoder(),failures,stop);consumer.registered(channel);
		try {
			for(int tag=1;tag<=66;tag++) {
				consumer.onMessage(message(s,consumer,tag),channel);if(tag==1)assertTrue(entered.await(2,TimeUnit.SECONDS));
			}
			assertNotSame(Thread.currentThread(),workerThread.get());assertEquals(66,s.onlyIncident().getErrorCount());assertEquals(66,s.committedReceipts.size());
			verify(channel,times(66)).basicAck(anyLong(),eq(false));verifyNoInteractions(stop,realtime);
			var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(bridge,"worker");assertEquals(1,executor.getPoolSize());assertEquals(64,executor.getQueue().size());assertEquals(1,executor.getMaximumPoolSize());
		} finally {release.countDown();bridge.close();}
		assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(bridge,"worker")).isTerminated());
	}
	private Message message(EngineTestStore s,AlertLogConsumer consumer,int tag) {
		var mapper=new ObjectMapper();UUID id=UUID.randomUUID();var root=mapper.createObjectNode().put("event_id",id.toString()).put("event_type","critical.log")
				.put("occurred_at",s.clock.instant().toString()).put("producer","processing").put("schema_version",1);
		root.putObject("payload").put("event_id",id.toString()).put("environment_id",s.env.toString()).put("application_id",UUID.randomUUID().toString())
				.put("level","ERROR").put("message","Database timeout").put("trace_id","").put("timestamp",s.clock.instant().toString()).put("received_at",s.clock.instant().toString());
		var p=new MessageProperties();p.setDeliveryTag(tag);p.setConsumerQueue("alert.queue");p.setConsumerTag(consumer.consumerTag());return new Message(mapper.writeValueAsBytes(root),p);
	}
	static class ManualExecutor extends AbstractExecutorService {
		final ArrayDeque<Runnable> pending=new ArrayDeque<>();boolean stopped;long waitSeconds;int shutdownCalls;Runnable onSubmit=() -> { };
		@Override public void execute(Runnable task) {if(stopped || pending.size()==64)throw new RejectedExecutionException();onSubmit.run();pending.add(task);}
		void drain() {while(!pending.isEmpty())pending.remove().run();}
		@Override public void shutdown() {stopped=true;}
		@Override public List<Runnable> shutdownNow() {stopped=true;shutdownCalls++;var result=List.copyOf(pending);pending.clear();return result;}
		@Override public boolean isShutdown() {return stopped;}
		@Override public boolean isTerminated() {return stopped && pending.isEmpty();}
		@Override public boolean awaitTermination(long time,TimeUnit unit) {waitSeconds=unit.toSeconds(time);return isTerminated();}
	}
}
