package com.h.vanioak.modules.realtime.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RealtimeFailurePublisherTest {
	final RabbitTemplate rabbit = mock(RabbitTemplate.class);
	final PublisherCallbackChannel channel = mock(PublisherCallbackChannel.class);
	final List<Message> bodies = new CopyOnWriteArrayList<>();
	final List<CorrelationData> correlations = new CopyOnWriteArrayList<>();
	final List<Long> delays = new CopyOnWriteArrayList<>();

	@BeforeEach void setup() {
		when(channel.isOpen()).thenReturn(true);
		doAnswer(call -> ((RabbitOperations.OperationsCallback<?>) call.getArgument(0)).doInRabbit(rabbit))
				.when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
		doAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(channel))
				.when(rabbit).execute(any(ChannelCallback.class));
	}
	RealtimeFailurePublisher publisher() {
		return new RealtimeFailurePublisher(rabbit, Clock.systemUTC(), Duration.ofMillis(100), delays::add);
	}
	RealtimeEventDecoder.Decoded invalid() {
		return new RealtimeEventDecoder().decode(RealtimeEventDecoderTest.message(new byte[] {(byte) 0xff, 1}));
	}
	void sends(boolean ack, boolean returned) {
		doAnswer(call -> {
			Message message = call.getArgument(2); CorrelationData correlation = call.getArgument(3);
			bodies.add(message); correlations.add(correlation);
			assertEquals("dead-letter.exchange", call.getArgument(0));
			assertEquals("dead-letter.log", call.getArgument(1));
			if (returned) correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "dead-letter.exchange", "dead-letter.log"));
			correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
	}

	@Test void confirmedHandoffHasExactShapeAndRawFidelity() throws Exception {
		sends(true, false);
		try (var publisher = publisher()) {
			assertTrue(publisher.publish(invalid(), null).toCompletableFuture().get(2, TimeUnit.SECONDS));
			var tree = RealtimeJson.reader().readTree(bodies.getFirst().getBody());
			assertEquals(6, tree.size()); assertEquals(5, tree.get("payload").size());
			assertArrayEquals(invalid().raw(), Base64.getDecoder().decode(tree.get("payload").get("original_message").get("body_base64").asString()));
			verify(rabbit).setMandatory(true);
			assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT, bodies.getFirst().getMessageProperties().getDeliveryMode());
		}
	}

	@Test void nackExhaustionAndReturnDespiteAckNeverSucceedAndKeepStableFallback() throws Exception {
		for (boolean returned : List.of(false, true)) {
			bodies.clear(); correlations.clear(); delays.clear(); sends(returned, returned);
			try (var publisher = publisher()) {
				assertFalse(publisher.publish(invalid(), null).toCompletableFuture().get(2, TimeUnit.SECONDS));
				assertEquals(3, bodies.size()); assertEquals(List.of(5000L, 5000L), delays);
				assertEquals(3, correlations.stream().map(CorrelationData::getId).distinct().count());
				for (Message body : bodies) {
					assertArrayEquals(bodies.getFirst().getBody(), body.getBody());
					assertEquals(bodies.getFirst().getMessageProperties().getMessageId(), body.getMessageProperties().getMessageId());
				}
			}
		}
	}

	@Test void unknownConfirmRetainsSlotAndStopsFurtherAdmission() throws Exception {
		doAnswer(call -> { correlations.add(call.getArgument(3)); return null; })
				.when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		try (var publisher = publisher()) {
			assertFalse(publisher.publish(invalid(), null).toCompletableFuture().get(2, TimeUnit.SECONDS));
			assertTrue(publisher.occupied()); assertEquals(1, correlations.size());
			assertTrue(publisher.publish(invalid(), null).toCompletableFuture().isCompletedExceptionally());
			assertFalse(correlations.getFirst().getFuture().isCancelled());
		}
	}

	@Test void callerCancellationDoesNotCancelNativeConfirm() throws Exception {
		var sent = new LinkedBlockingQueue<CorrelationData>();
		doAnswer(call -> { sent.add(call.getArgument(3)); return null; })
				.when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		try (var publisher = new RealtimeFailurePublisher(rabbit, Clock.systemUTC(), Duration.ofSeconds(2), delays::add)) {
			var view = publisher.publish(invalid(), null).toCompletableFuture();
			var nativeConfirm = sent.poll(2, TimeUnit.SECONDS); assertNotNull(nativeConfirm);
			view.cancel(true);
			assertTrue(publisher.occupied()); assertFalse(nativeConfirm.getFuture().isCancelled());
			nativeConfirm.getFuture().complete(new CorrelationData.Confirm(true, null));
		}
	}

	@Test void exactInputBase64FitsCompleteBudgetAndObjectPrecisionSurvives() throws Exception {
		sends(true, false);
		byte[] raw = new byte[RealtimeJson.INPUT_LIMIT]; Arrays.fill(raw, (byte) 0xff);
		try (var publisher = publisher()) {
			assertTrue(publisher.publish(new RealtimeEventDecoder().decode(RealtimeEventDecoderTest.message(raw)), null)
					.toCompletableFuture().get(2, TimeUnit.SECONDS));
			assertTrue(bodies.getFirst().getBody().length <= RealtimeJson.DLQ_LIMIT);
		}
		bodies.clear();
		var object = RealtimeEventDecoderTest.event(); object.put("producer", "invalid");
		String integer = "9".repeat(100);
		object.put("unknownInteger", new java.math.BigInteger(integer));
		try (var publisher = publisher()) {
			assertTrue(publisher.publish(new RealtimeEventDecoder().decode(RealtimeEventDecoderTest.message(object)), null)
					.toCompletableFuture().get(2, TimeUnit.SECONDS));
			var tree = RealtimeJson.reader().readTree(bodies.getFirst().getBody());
			assertEquals(new java.math.BigInteger(integer), tree.get("payload").get("original_message").get("unknownInteger").bigIntegerValue());
			assertEquals(RealtimeEventDecoderTest.ID, bodies.getFirst().getMessageProperties().getMessageId());
		}
	}

	@Test void completeSerializationOverflowNeverProducesBytes() {
		var mapper = RealtimeJson.reader();
		assertThrows(RealtimeJson.CapacityException.class, () -> RealtimeJson.encode(mapper,
				mapper.createObjectNode().put("x", "x".repeat(RealtimeJson.DLQ_LIMIT)), RealtimeJson.DLQ_LIMIT));
	}

	@Test void sendFailureBeforeCorrelationRegistrationCannotFreeUnknownNativeSlot() throws Exception {
		doThrow(new IllegalStateException("send outcome unknown")).when(rabbit)
				.send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		try (var publisher = publisher()) {
			assertFalse(publisher.publish(invalid(), null).toCompletableFuture().get(2, TimeUnit.SECONDS));
			assertTrue(publisher.occupied()); assertTrue(delays.isEmpty());
			verify(rabbit, times(1)).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		}
	}

	@Test void blockedSocketAttemptKeepsSoleOwnershipUntilActualWorkReturns() throws Exception {
		var entered = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		doAnswer(call -> {
			entered.countDown(); release.await();
			((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		try (var publisher = publisher()) {
			var pending = publisher.publish(invalid(), null).toCompletableFuture();
			assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(pending.isDone()); assertTrue(publisher.occupied());
			publisher.close(); assertTrue(publisher.occupied());
			assertTrue(publisher.publish(invalid(), null).toCompletableFuture().isCompletedExceptionally());
			release.countDown(); pending.get(2, TimeUnit.SECONDS);
		} finally { release.countDown(); }
	}
}
