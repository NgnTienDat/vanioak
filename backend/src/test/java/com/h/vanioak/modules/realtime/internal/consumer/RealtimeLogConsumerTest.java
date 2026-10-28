package com.h.vanioak.modules.realtime.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.ConsumerTagStrategy;
import org.springframework.context.ApplicationEventPublisher;

import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;

class RealtimeLogConsumerTest {
	@TempDir Path directory;
	final SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
	final RealtimeService service = mock(RealtimeService.class);
	final RealtimeFailurePublisher failures = mock(RealtimeFailurePublisher.class);
	final Channel channel = mock(Channel.class);
	final RealtimeReadiness readiness = new RealtimeReadiness();
	RealtimeLogConsumer consumer;
	ApplicationEventPublisher events;
	String tag;

	@BeforeEach void setup() {
		when(channel.isOpen()).thenReturn(true);
		when(service.publishLog(any())).thenReturn(new RealtimeFacade.Fanout(true, 0, 0));
		consumer = new RealtimeLogConsumer(container, new RealtimeEventDecoder(), failures, service, readiness,
				new RealtimeRecoveryFence(directory.resolve("realtime.fence")));
		var strategy = ArgumentCaptor.forClass(ConsumerTagStrategy.class);
		verify(container).setConsumerTagStrategy(strategy.capture());
		tag = strategy.getValue().createConsumerTag("realtime.queue");
		var publisher = ArgumentCaptor.forClass(ApplicationEventPublisher.class);
		verify(container).setApplicationEventPublisher(publisher.capture()); events = publisher.getValue();
	}
	@AfterEach void close() { consumer.close(); }
	void ready() { consumer.start(); events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel), "realtime.queue", tag)); }
	Message valid() { return RealtimeEventDecoderTest.message(RealtimeEventDecoderTest.event()); }

	@Test void onlyExactExclusiveRegistrationOpensReadinessAndZeroSubscribersAck() throws Exception {
		consumer.start(); assertFalse(readiness.ready());
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel), "other.queue", tag));
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel), "realtime.queue", "wrong-tag"));
		assertFalse(readiness.ready());
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel), "realtime.queue", tag));
		assertTrue(readiness.ready()); consumer.onMessage(valid(), channel);
		verify(channel).basicAck(1L, false); verifyNoInteractions(failures);
	}

	@Test void completeInputOrFrameCapacityPausesWithoutAckAndDoesNotRestart() throws Exception {
		ready();
		when(service.publishLog(any())).thenReturn(new RealtimeFacade.Fanout(false, 0, 0));
		consumer.onMessage(valid(), channel);
		assertTrue(readiness.paused()); assertFalse(readiness.ready());
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		consumer.start(); events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel), "realtime.queue", tag));
		assertFalse(readiness.ready()); verify(container, times(1)).start();
		verify(container, timeout(1000)).stop(); verify(service).unavailable();
		assertTrue(Files.exists(directory.resolve("realtime.fence")));
	}

	@Test void oversizedRawInputNeverReachesPublisherOrFanout() throws Exception {
		ready(); consumer.onMessage(RealtimeEventDecoderTest.message(new byte[RealtimeJson.INPUT_LIMIT + 1]), channel);
		assertTrue(readiness.paused()); verify(service, never()).publishLog(any());
		verify(failures, never()).publish(any(), nullable(String.class));
	}

	@Test void malformedDeliveryWaitsForConfirmedHandoffBeforeAck() throws Exception {
		ready(); var confirmation = new CompletableFuture<Boolean>(); var called = new CountDownLatch(1);
		when(failures.publish(any(), nullable(String.class))).thenAnswer(call -> { called.countDown(); return confirmation; });
		var worker = Executors.newSingleThreadExecutor();
		try {
			var task = worker.submit(() -> consumer.onMessage(RealtimeEventDecoderTest.message(new byte[] {(byte) 0xff}), channel));
			assertTrue(called.await(2, TimeUnit.SECONDS));
			verify(channel, never()).basicAck(anyLong(), anyBoolean());
			confirmation.complete(true); task.get(2, TimeUnit.SECONDS);
			verify(channel).basicAck(1L, false);
		} finally { confirmation.complete(false); worker.shutdownNow(); }
	}

	@Test void failedTransferAndDuplicateCallbacksNeverAckUnaccountedTags() throws Exception {
		ready(); when(failures.publish(any(), nullable(String.class))).thenReturn(CompletableFuture.completedFuture(false));
		consumer.onMessage(RealtimeEventDecoderTest.message(new byte[] {(byte) 0xff}), channel);
		consumer.onMessage(valid(), channel);
		verify(channel, never()).basicAck(anyLong(), anyBoolean()); assertTrue(readiness.paused());
	}

	@Test void foreignChannelAndUnknownAckOutcomeFenceWithoutRetryingDeliveryTag() throws Exception {
		ready(); doThrow(new java.io.IOException("Unknown ACK result")).when(channel).basicAck(1L, false);
		consumer.onMessage(valid(), channel);
		consumer.onMessage(valid(), channel);
		verify(channel, times(1)).basicAck(1L, false); assertTrue(readiness.paused());
	}

	@Test void sourceFailureStopsOwnContainerAndNewProcessCannotClearFence() throws Exception {
		ready(); events.publishEvent(new ListenerContainerConsumerFailedEvent(container, "source lost", null, false));
		assertTrue(readiness.paused()); verify(container, timeout(1000)).stop();
		var nextContainer = mock(SimpleMessageListenerContainer.class); var nextState = new RealtimeReadiness();
		try (var next = new RealtimeLogConsumer(nextContainer, new RealtimeEventDecoder(), failures, service, nextState,
				new RealtimeRecoveryFence(directory.resolve("realtime.fence")))) {
			next.start(); assertTrue(nextState.paused()); verify(nextContainer, never()).start();
		}
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
		verify(channel, never()).basicReject(anyLong(), anyBoolean());
	}

	@Test void foreignChannelCannotAckAfterActualSourceCloseMakesRedeliveryEligible() throws Exception {
		ready();
		var shutdown = ArgumentCaptor.forClass(com.rabbitmq.client.ShutdownListener.class);
		verify(channel).addShutdownListener(shutdown.capture());
		shutdown.getValue().shutdownCompleted(new com.rabbitmq.client.ShutdownSignalException(false, false, null, channel));
		var foreign = mock(Channel.class); when(foreign.isOpen()).thenReturn(true);
		consumer.onMessage(valid(), foreign);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
		verify(foreign, never()).basicAck(anyLong(), anyBoolean());
		assertTrue(readiness.paused());
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}
}
