package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import com.rabbitmq.client.Channel;

class AlertConsumerLifecycleTest {
	@TempDir Path directory;
	final CachingConnectionFactory factory=mock(CachingConnectionFactory.class);
	final Connection connection=mock(Connection.class);
	final org.springframework.amqp.rabbit.connection.PublisherCallbackChannel probe=mock(org.springframework.amqp.rabbit.connection.PublisherCallbackChannel.class);
	final SimpleMessageListenerContainer container=mock(SimpleMessageListenerContainer.class);
	final AlertFailurePublisher publisher=mock(AlertFailurePublisher.class);
	final AlertEngineFacade engine=mock(AlertEngineFacade.class);
	@BeforeEach void setup() throws Exception {
		when(factory.isPublisherConfirms()).thenReturn(true);when(factory.isPublisherReturns()).thenReturn(true);
		when(factory.createConnection()).thenReturn(connection);when(connection.createChannel(false)).thenReturn(probe);when(probe.isOpen()).thenReturn(true);
		when(publisher.awaitStopped(anyLong())).thenReturn(true);
	}
	AlertConsumerProperties properties(Path fence) {return new AlertConsumerProperties(true,3,List.of(Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofSeconds(120)),Duration.ofSeconds(5),fence,null);}
	AlertConsumerLifecycle lifecycle(Path fence,AlertLogConsumer consumer) {return new AlertConsumerLifecycle(factory,properties(fence),container,publisher,consumer);}
	@Test void passiveOnlyProbesPrecedeForcedFenceAndListenerStartReadinessNeedsRegistration() throws Exception {
		Path fence=directory.resolve("alert.fence");var holder=new AtomicReference<AlertConsumerLifecycle>();
		var consumer=new AlertLogConsumer(engine,new AlertEventDecoder(),publisher,() -> holder.get().stop());
		try(var l=lifecycle(fence,consumer)) {
			holder.set(l);doAnswer(call -> {assertEquals("ALERT_ARMED_REVIEW_REQUIRED\n",Files.readString(fence));return null;}).when(container).start();
			l.start();assertFalse(l.ready());var nativeSource=mock(Channel.class);when(nativeSource.isOpen()).thenReturn(true);consumer.registered(nativeSource);assertTrue(l.ready());
			var order=inOrder(probe,container);for(String queue:List.of("alert.queue","alert.retry.queue","dead-letter.queue"))order.verify(probe).queueDeclarePassive(queue);
			order.verify(probe).exchangeDeclarePassive("critical.exchange");order.verify(probe).exchangeDeclarePassive("dead-letter.exchange");order.verify(container).start();
			verify(probe,never()).queueDeclare(anyString(),anyBoolean(),anyBoolean(),anyBoolean(),any());
			consumer.stop();assertFalse(l.ready());l.start();verify(container,times(1)).start();
		}
		assertTrue(Files.exists(fence));
	}
	@Test void missingPassiveResourcePreventsFenceAndStartWithoutRetry() throws Exception {
		when(probe.queueDeclarePassive("alert.retry.queue")).thenThrow(new java.io.IOException("not found"));Path fence=directory.resolve("alert.fence");
		try(var l=lifecycle(fence,mock(AlertLogConsumer.class))) {l.start();l.start();assertFalse(l.ready());assertFalse(Files.exists(fence));verify(container,never()).start();verify(factory,times(1)).createConnection();}
	}
	@Test void missingConfirmOrReturnCapabilityNeverOpensConnection() {
		when(factory.isPublisherReturns()).thenReturn(false);
		try(var l=lifecycle(directory.resolve("alert.fence"),mock(AlertLogConsumer.class))) {l.start();verify(factory,never()).createConnection();verify(container,never()).start();}
	}
	@Test void existingMarkerSurvivesAndReconstructedInstanceCannotRestart() throws Exception {
		Path fence=directory.resolve("alert.fence");var p=properties(fence);
		try(var l=new AlertConsumerLifecycle(factory,p,container,publisher,mock(AlertLogConsumer.class))) {l.start();}
		byte[] original=Files.readAllBytes(fence);clearInvocations(factory,container);
		try(var l=new AlertConsumerLifecycle(factory,p,container,publisher,mock(AlertLogConsumer.class))) {l.start();verify(factory,never()).createConnection();verify(container,never()).start();assertFalse(l.ready());}
		assertArrayEquals(original,Files.readAllBytes(fence));
	}
	@Test void startupFailureAfterArmingKeepsFenceAndStopsOnlyOwnedListener() throws Exception {
		Path fence=directory.resolve("alert.fence");doThrow(new IllegalStateException("start failure")).when(container).start();
		try(var l=lifecycle(fence,mock(AlertLogConsumer.class))) {l.start();assertFalse(l.ready());}
		assertTrue(Files.exists(fence));verify(container).stop();verify(factory,never()).resetConnection();
	}
	@Test void concurrentStopCannotLeaveListenerStartedAfterItsStop() throws Exception {
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(call -> {entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return null;}).when(container).start();
		var l=lifecycle(directory.resolve("alert.fence"),mock(AlertLogConsumer.class));
		try(var threads=Executors.newSingleThreadExecutor()) {
			var starting=threads.submit(l::start);try {assertTrue(entered.await(2,TimeUnit.SECONDS));l.stop();assertFalse(l.ready());}finally {release.countDown();}
			starting.get(2,TimeUnit.SECONDS);
		}finally {l.close();}
		var order=inOrder(container);order.verify(container).start();order.verify(container).stop();
	}
	@Test void unknownPublisherOwnershipIsNotReportedSettled() throws Exception {
		when(publisher.occupied()).thenReturn(true);var l=lifecycle(directory.resolve("alert.fence"),mock(AlertLogConsumer.class));l.close();l.close();assertFalse(l.shutdownSettled());
		var remaining=org.mockito.ArgumentCaptor.forClass(Long.class);verify(publisher,times(1)).awaitStopped(remaining.capture());assertTrue(remaining.getValue()>=0 && remaining.getValue()<=TimeUnit.SECONDS.toNanos(5));
	}
	@Test void disabledEvenDirectLifecycleStartDoesNotProbeOrArm() {
		var p=new AlertConsumerProperties(false,0,List.of(),Duration.ofSeconds(1),null,null);
		try(var l=new AlertConsumerLifecycle(factory,p,container,publisher,mock(AlertLogConsumer.class))) {l.start();verify(factory,never()).createConnection();verify(container,never()).start();}
	}
	@Test void completedStopRequiresNativeSourceClosureNotMerelyConsumerPause() throws Exception {
		for(boolean retired:List.of(false,true)) {
			reset(container);var nativeChannel=mock(Channel.class);when(nativeChannel.isOpen()).thenReturn(true);
			var holder=new AtomicReference<AlertConsumerLifecycle>();var consumer=new AlertLogConsumer(engine,new AlertEventDecoder(),publisher,() -> holder.get().stop());
			var l=lifecycle(directory.resolve("alert-"+retired+".fence"),consumer);holder.set(l);
			doAnswer(call -> {if(retired)when(nativeChannel.isOpen()).thenReturn(false);return null;}).when(container).stop();
			l.start();consumer.registered(nativeChannel);assertTrue(l.ready());l.close();assertFalse(l.ready());assertEquals(retired,l.shutdownSettled());
		}
	}
}
