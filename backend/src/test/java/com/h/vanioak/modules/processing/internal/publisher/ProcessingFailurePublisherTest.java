package com.h.vanioak.modules.processing.internal.publisher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class ProcessingFailurePublisherTest {

	private static final Instant NOW = Instant.parse("2026-10-10T01:02:03.123456789Z");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
	private final List<Sent> sent = new ArrayList<>();
	private ProcessingFailurePublisher publisher;

	@BeforeEach
	void setUp() {
		publisher = publisher(Duration.ofSeconds(5), 3, List.of(Duration.ofSeconds(5),
				Duration.ofSeconds(30), Duration.ofSeconds(120)));
		doAnswer(call -> {
			var send = new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			sent.add(send);
			send.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
	}

	@Test
	void retriesPreserveExactBytesAndIdentityWithIndependentConfirmedTransfers() {
		var original = message("{ \"event_id\":\"" + UUID.randomUUID() + "\",\"payload\":{\"received_at\":\"original\"}}");
		original.getMessageProperties().setContentEncoding("UTF-8");
		original.getMessageProperties().setCorrelationId("original-correlation");
		original.getMessageProperties().setHeader("CC", List.of("must-not-copy"));
		original.getMessageProperties().setExpiration("1");
		String id = original.getMessageProperties().getMessageId();
		for (int count = 0; count < 3; count++) {
			original.getMessageProperties().setHeader(ProcessingFailurePublisher.RETRY_COUNT, count);
			publisher.retry(original);
			Sent send = sent.getLast();
			assertEquals("", send.exchange());
			assertEquals("raw.retry.queue", send.key());
			assertArrayEquals(original.getBody(), send.message().getBody());
			var properties = send.message().getMessageProperties();
			assertEquals(id, properties.getMessageId());
			assertEquals("application/json", properties.getContentType());
			assertEquals("UTF-8", properties.getContentEncoding());
			assertEquals("original-correlation", properties.getCorrelationId());
			assertEquals(MessageDeliveryMode.PERSISTENT, properties.getDeliveryMode());
			assertEquals(Set.of(ProcessingFailurePublisher.RETRY_COUNT), properties.getHeaders().keySet());
			assertEquals(count + 1, ((Number) properties.getHeader(ProcessingFailurePublisher.RETRY_COUNT)).intValue());
			assertEquals(List.of("5000", "30000", "120000").get(count), properties.getExpiration());
			assertEquals(count, ((Number) original.getMessageProperties().getHeader(ProcessingFailurePublisher.RETRY_COUNT)).intValue());
			assertEquals("1", original.getMessageProperties().getExpiration());
		}
		assertEquals(3, sent.stream().map(send -> send.correlation().getId()).distinct().count());
	}

	@Test
	void validatesTransportCounterWithoutTrustingRedeliveryOrDeathHistory() {
		var raw = message("{}");
		raw.getMessageProperties().setRedelivered(true);
		raw.getMessageProperties().setHeader("x-death", List.of("untrusted"));
		assertEquals(0, publisher.retryCount(raw));
		for (Object value : List.of((byte) 1, (short) 2, 3, 0L)) {
			raw.getMessageProperties().setHeader(ProcessingFailurePublisher.RETRY_COUNT, value);
			assertEquals(((Number) value).intValue(), publisher.retryCount(raw));
		}
		for (Object value : List.of(-1, 4, Long.MAX_VALUE, "1", 1.0, true)) {
			raw.getMessageProperties().setHeader(ProcessingFailurePublisher.RETRY_COUNT, value);
			assertEquals(-1, publisher.retryCount(raw));
			publisher.retry(raw);
			assertEquals("INVALID_RETRY_METADATA", payload().get("reason").asString());
		}
		raw.getMessageProperties().getHeaders().put(ProcessingFailurePublisher.RETRY_COUNT, null);
		assertEquals(-1, publisher.retryCount(raw));
	}

	@Test
	void exhaustionAndZeroRetryBudgetGoDirectlyToDlq() {
		var raw = message("{}");
		raw.getMessageProperties().setHeader(ProcessingFailurePublisher.RETRY_COUNT, 3);
		publisher.retry(raw);
		assertEquals("PROCESSING_RETRY_EXHAUSTED", payload().get("reason").asString());
		assertEquals("dead-letter.exchange", sent.getLast().exchange());
		assertEquals("dead-letter.log", sent.getLast().key());
		publisher = publisher(Duration.ofSeconds(5), 0, List.of());
		raw.getMessageProperties().getHeaders().clear();
		publisher.retry(raw);
		assertEquals("PROCESSING_RETRY_EXHAUSTED", payload().get("reason").asString());
		assertEquals(Duration.ofSeconds(5), publisher.cooldown());
	}

	@Test
	void wholeBatchDlqHasExactContractAndPreservesOriginalObjectAndPrecision() {
		String batchId = UUID.randomUUID().toString();
		String logId = UUID.randomUUID().toString();
		var original = message("{\"event_id\":\"" + batchId + "\",\"payload\":{\"received_at\":\"original\","
				+ "\"logs\":[{\"event_id\":\"" + logId + "\",\"metadata\":{\"precise\":"
				+ "0.12345678901234567890123456789}}]}}");
		publisher.deadLetter(original, ProcessingFailurePublisher.INVALID_BATCH);
		var event = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.readTree(sent.getFirst().message().getBody());
		assertEquals(Set.of("event_id", "event_type", "occurred_at", "producer", "schema_version", "payload"),
				event.propertyNames());
		assertEquals(batchId, event.get("event_id").asString());
		assertEquals("dead-letter.log", event.get("event_type").asString());
		assertEquals("processing", event.get("producer").asString());
		assertEquals(1, event.get("schema_version").asInt());
		assertEquals(NOW.toString(), event.get("occurred_at").asString());
		var payload = event.get("payload");
		assertEquals(Set.of("event_id", "reason", "field", "original_message", "failed_at"), payload.propertyNames());
		assertEquals(batchId, payload.get("event_id").asString());
		assertEquals("INVALID_RAW_BATCH", payload.get("reason").asString());
		assertEquals("$", payload.get("field").asString());
		assertEquals(NOW.toString(), payload.get("failed_at").asString());
		var source = payload.get("original_message");
		assertEquals(logId, source.get("payload").get("logs").get(0).get("event_id").asString());
		assertEquals("original", source.get("payload").get("received_at").asString());
		assertEquals(new BigDecimal("0.12345678901234567890123456789"),
				source.get("payload").get("logs").get(0).get("metadata").get("precise").decimalValue());
		assertEquals(batchId, sent.getFirst().message().getMessageProperties().getMessageId());
		assertEquals(MessageDeliveryMode.PERSISTENT, sent.getFirst().message().getMessageProperties().getDeliveryMode());
		assertNull(sent.getFirst().message().getMessageProperties().getExpiration());
	}

	@Test
	void malformedNonObjectAndBinaryBodiesUseLosslessWrapperAndAmqpIdentityFallback() {
		for (byte[] bytes : List.of("{secret".getBytes(StandardCharsets.UTF_8), "[]".getBytes(StandardCharsets.UTF_8),
				"{} {}".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff, 0, 1}, new byte[0])) {
			var properties = new MessageProperties();
			String id = UUID.randomUUID().toString();
			properties.setMessageId(id);
			publisher.deadLetter(new Message(bytes, properties), ProcessingFailurePublisher.INVALID_BATCH);
			var payload = payload();
			assertEquals(id, payload.get("event_id").asString());
			assertEquals(Set.of("body_base64"), payload.get("original_message").propertyNames());
			assertArrayEquals(bytes, Base64.getDecoder().decode(payload.get("original_message").get("body_base64").asString()));
		}
		var raw = message("{\"event_id\":\"broken\"}");
		raw.getMessageProperties().setMessageId("broken");
		publisher.deadLetter(raw, ProcessingFailurePublisher.INVALID_BATCH);
		assertNotNull(UUID.fromString(payload().get("event_id").asString()));
		assertEquals(mapper.readTree(raw.getBody()), payload().get("original_message"));
	}

	@Test
	void allUnconfirmedTransferOutcomesFailSafelyOnBothRoutes() {
		for (boolean dlq : List.of(false, true)) {
			for (String failure : List.of("nack", "return", "timeout", "send", "exceptional", "cancelled", "interrupt")) {
				publisher = publisher(Duration.ofMillis(1), 3, List.of(Duration.ofSeconds(5),
						Duration.ofSeconds(30), Duration.ofSeconds(120)));
				doAnswer(call -> {
					Message message = call.getArgument(2);
					CorrelationData correlation = call.getArgument(3);
					switch (failure) {
						case "nack" -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "private reason"));
						case "return" -> {
							correlation.setReturned(new ReturnedMessage(message, 312, "private reason",
									call.getArgument(0), call.getArgument(1)));
							correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
						}
						case "send" -> throw new AmqpException("private body");
						case "exceptional" -> correlation.getFuture().completeExceptionally(new IllegalStateException("private"));
						case "cancelled" -> correlation.getFuture().cancel(false);
						case "interrupt" -> Thread.currentThread().interrupt();
						default -> { }
					}
					return null;
				}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
				var raw = message("{\"secret\":\"must not appear\"}");
				try {
					var exception = assertThrows(ProcessingException.class, () -> {
						if (dlq) publisher.deadLetter(raw, ProcessingFailurePublisher.INVALID_BATCH);
						else publisher.retry(raw);
					});
					assertEquals(ErrorCode.PUBLICATION_UNAVAILABLE, exception.getErrorCode());
					assertEquals("Log publication unavailable", exception.getMessage());
					assertNull(exception.getCause());
					assertEquals(failure.equals("interrupt"), Thread.currentThread().isInterrupted());
				} finally {
					Thread.interrupted();
				}
			}
		}
	}

	@Test
	void configurationRequiresExactPositiveDelayListAndSafeTimeout() {
		for (List<Duration> delays : List.of(List.<Duration>of(), List.of(Duration.ZERO),
				List.of(Duration.ofNanos(1)), List.of(Duration.ofSeconds(-1)))) {
			assertThrows(IllegalArgumentException.class, () -> publisher(Duration.ofSeconds(5), 1, delays));
		}
		assertThrows(IllegalArgumentException.class, () -> publisher(Duration.ZERO, 0, List.of()));
		assertThrows(IllegalArgumentException.class, () -> publisher(Duration.ofSeconds(1), -1, List.of()));
		assertFalse(sent.iterator().hasNext());
	}

	@Test
	void transferWaitsForItsOwnConfirmBeforeReturningOnEitherRoute() throws Exception {
		for (boolean dlq : List.of(false, true)) {
			var published = new java.util.concurrent.CompletableFuture<CorrelationData>();
			doAnswer(call -> {
				published.complete(call.getArgument(3));
				return null;
			}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
			try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
				var attempt = worker.submit(() -> {
					if (dlq) publisher.deadLetter(message("{}"), ProcessingFailurePublisher.INVALID_BATCH);
					else publisher.retry(message("{}"));
				});
				var correlation = published.get(2, java.util.concurrent.TimeUnit.SECONDS);
				assertFalse(attempt.isDone());
				correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
				attempt.get(2, java.util.concurrent.TimeUnit.SECONDS);
			}
		}
	}

	private ProcessingFailurePublisher publisher(Duration timeout, int count, List<Duration> delays) {
		return new ProcessingFailurePublisher(rabbit, mapper, timeout, count, delays, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private Message message(String body) {
		var properties = new MessageProperties();
		properties.setMessageId(UUID.randomUUID().toString());
		properties.setContentType("application/json");
		return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
	}

	private tools.jackson.databind.JsonNode payload() {
		return mapper.readTree(sent.getLast().message().getBody()).get("payload");
	}

	private record Sent(String exchange, String key, Message message, CorrelationData correlation) { }
}