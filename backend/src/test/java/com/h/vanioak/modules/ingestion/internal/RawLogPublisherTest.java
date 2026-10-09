package com.h.vanioak.modules.ingestion.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;

import tools.jackson.databind.json.JsonMapper;

class RawLogPublisherTest {
	private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final RawLogPublisher publisher = new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5));

	@Test
	void persistentJsonIsSentToExactRouteAndReturnsOnlyAfterConfirmation() throws Exception {
		var correlations = new LinkedBlockingQueue<CorrelationData>();
		var messages = new LinkedBlockingQueue<Message>();
		doAnswer(call -> {
			messages.add(call.getArgument(2));
			correlations.add(call.getArgument(3));
			return null;
		}).when(rabbit).send(eq("raw.exchange"), eq("raw.log"), any(Message.class), any(CorrelationData.class));
		var event = event();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var work = executor.submit(() -> publisher.publish(event));
			var correlation = correlations.poll(2, TimeUnit.SECONDS);
			try {
				assertFalse(work.isDone());
				Message message = messages.remove();
				assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
				assertEquals("application/json", message.getMessageProperties().getContentType());
				assertEquals(event.eventId().toString(), message.getMessageProperties().getMessageId());
				assertEquals(event.eventId().toString(), correlation.getId());
				assertEquals(mapper.valueToTree(event), mapper.readTree(message.getBody()));
			} finally {
				if (correlation != null) correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			}
			work.get(2, TimeUnit.SECONDS);
		}
	}

	@Test
	void nackReturnedAckAndFailedFutureNeverSucceed() {
		for (String failure : List.of("nack", "return", "future")) {
			doAnswer(call -> {
				CorrelationData correlation = call.getArgument(3);
				if (failure.equals("return")) correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312,
						"Sensitive broker detail", "raw.exchange", "raw.log"));
				if (failure.equals("future")) correlation.getFuture().completeExceptionally(new IllegalStateException("Private detail"));
				else correlation.getFuture().complete(new CorrelationData.Confirm(!failure.equals("nack"), "Private reason"));
				return null;
			}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
			unavailable(publisher);
		}
	}

	@Test
	void timeoutAndSendFailureAreSafeUnavailable() {
		unavailable(new RawLogPublisher(rabbit, mapper, Duration.ofMillis(1)));
		doAnswer(call -> { throw new AmqpConnectException(new java.net.ConnectException("Private detail")); })
				.when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		unavailable(publisher);
	}

	@Test
	void interruptionRestoresFlagAndInvalidTimeoutFailsConfiguration() {
		try {
			Thread.currentThread().interrupt();
			unavailable(publisher);
			assertTrue(Thread.currentThread().isInterrupted());
		} finally { Thread.interrupted(); }
		for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)})
			assertThrows(IllegalArgumentException.class, () -> new RawLogPublisher(rabbit, mapper, timeout));
	}

	@Test
	void topologyIsDurableWithoutCapacityOrOverflowArguments() {
		var declarations = new IngestionRabbitConfiguration().rawLogTopology();
		var queue = declarations.getDeclarablesByType(org.springframework.amqp.core.Queue.class).getFirst();
		var exchange = declarations.getDeclarablesByType(org.springframework.amqp.core.DirectExchange.class).getFirst();
		var binding = declarations.getDeclarablesByType(org.springframework.amqp.core.Binding.class).getFirst();
		assertTrue(queue.isDurable());
		assertFalse(queue.isExclusive());
		assertFalse(queue.isAutoDelete());
		assertTrue(queue.getArguments() == null || queue.getArguments().isEmpty());
		assertTrue(exchange.isDurable());
		assertFalse(exchange.isAutoDelete());
		assertEquals("raw.queue", binding.getDestination());
		assertEquals("raw.exchange", binding.getExchange());
		assertEquals("raw.log", binding.getRoutingKey());
	}

	private void unavailable(RawLogPublisher target) {
		var failure = assertThrows(IngestionException.class, () -> target.publish(event()));
		assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.getErrorCode());
		assertEquals("Service unavailable", failure.getMessage());
	}

	private RawLogBatchEvent event() {
		Instant time = Instant.parse("2026-10-09T10:00:00Z");
		return new RawLogBatchEvent(UUID.randomUUID(), "raw.log", time, "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), time,
						List.of(new RawLogBatchEvent.Log(UUID.randomUUID(), "host", "INFO", "message", time, "trace", Map.of()))));
	}
}
