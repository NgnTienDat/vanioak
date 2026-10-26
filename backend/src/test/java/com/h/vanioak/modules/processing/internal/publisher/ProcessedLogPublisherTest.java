package com.h.vanioak.modules.processing.internal.publisher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.config.ProcessingRabbitConfiguration;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ProcessedLogPublisherTest {

	private static final Instant TIMESTAMP = Instant.parse("2026-10-10T01:02:03.123456789Z");
	private static final Instant RECEIVED = Instant.parse("2026-10-10T01:02:04.987654321Z");
	private static final Instant PROCESSED = Instant.parse("2026-10-10T01:02:05.111222333Z");
	private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final ProcessedLogPublisher publisher = new ProcessedLogPublisher(rabbit, mapper, Duration.ofSeconds(5));
	private final List<Sent> sent = new ArrayList<>();

	@BeforeEach
	void confirmedSends() {
		var channel = mock(PublisherCallbackChannel.class);
		doAnswer(call -> ((RabbitOperations.OperationsCallback<?>) call.getArgument(0)).doInRabbit(rabbit))
				.when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
		doAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(channel))
				.when(rabbit).execute(any(ChannelCallback.class));
		doAnswer(call -> {
			var publication = new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			sent.add(publication);
			publication.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
	}

	@Test
	void publishesExactSingleLogContractsForAllFourLevels() {
		for (String level : List.of("INFO", "WARN", "ERROR", "CRITICAL")) {
			sent.clear();
			NormalizedLog log = log(level, "{\"nested\":[1,true,null,{\"text\":\"value\"}],"
					+ "\"precise\":0.12345678901234567890123456789,\"application_id\":\"conflicting-id\"}");
			publisher.publish(log);
			assertEquals(level.equals("INFO") || level.equals("WARN") ? 1 : 2, sent.size());
			for (int index = 0; index < sent.size(); index++) {
				assertContract(log, sent.get(index), index == 1);
			}
			assertEquals(123456789, log.timestamp().getNano());
			assertEquals(987654321, log.receivedAt().getNano());
			assertEquals(111222333, log.processedAt().getNano());
		}
	}

	@Test
	void publishesEachRawBatchLogWithItsOwnIdentityAndAuthenticatedScope() {
		var source = log("INFO", "{}");
		var raw = new RawLogBatchEvent(UUID.randomUUID(), "raw.log", RECEIVED, "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), source.applicationId(), source.environmentId(), RECEIVED,
						List.of(new RawLogBatchEvent.Log(source.eventId(), "host", "INFO", "first", TIMESTAMP, "trace", Map.of()),
								new RawLogBatchEvent.Log(UUID.randomUUID(), "host", "ERROR", "second", TIMESTAMP, "trace",
										Map.of("decimal", new BigDecimal("0.12345678901234567890123456789"))))));
		var logs = normalizer(mapper, Clock.fixed(PROCESSED, ZoneOffset.UTC))
				.normalize(mapper.writeValueAsBytes(raw));
		publisher.publishBatch(logs);
		assertEquals(3, sent.size());
		assertContract(logs.get(0), sent.get(0), false);
		assertContract(logs.get(1), sent.get(1), false);
		assertContract(logs.get(1), sent.get(2), true);
		assertNotEquals(raw.eventId(), logs.get(0).eventId());
		assertNotEquals(logs.get(0).eventId(), logs.get(1).eventId());
	}

	@Test
	void pipelinesProcessedAndCriticalAndWaitsForBothOutOfOrderConfirmations() throws Exception {
		var sends = new LinkedBlockingQueue<Sent>();
		doAnswer(call -> {
			sends.add(new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		var executor = Executors.newSingleThreadExecutor();
		try {
			var work = executor.submit(() -> publisher.publish(log("ERROR", "{}")));
			Sent processed = sends.poll(2, TimeUnit.SECONDS);
			assertNotNull(processed);
			assertEquals("processed.log", processed.key());
			assertFalse(work.isDone());
			Sent critical = sends.poll(2, TimeUnit.SECONDS);
			assertNotNull(critical);
			assertEquals("critical.log", critical.key());
			assertFalse(work.isDone());
			critical.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			assertFalse(work.isDone());
			processed.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			work.get(2, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void nackReturnTimeoutSendFutureCancellationAndInterruptionFailEitherRouteSafely() {
		for (String route : List.of("processed.log", "critical.log")) {
			for (String failure : List.of("nack", "return", "timeout", "send", "future", "cancel", "interrupt")) {
				sent.clear();
				doAnswer(call -> {
					var publication = new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
					sent.add(publication);
					var correlation = publication.correlation();
					if (!route.equals(publication.key())) {
						correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
						return null;
					}
					switch (failure) {
						case "nack" -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "sensitive reason"));
						case "return" -> {
							correlation.setReturned(new ReturnedMessage(publication.message(), 312, "sensitive reason",
									publication.exchange(), publication.key()));
							correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
						}
						case "send" -> throw new AmqpConnectException(new ConnectException("sensitive connection detail"));
						case "future" -> correlation.getFuture().completeExceptionally(new IllegalStateException("sensitive detail"));
						case "cancel" -> correlation.getFuture().cancel(false);
						case "interrupt" -> Thread.currentThread().interrupt();
						case "timeout" -> { }
						default -> throw new AssertionError(failure);
					}
					return null;
				}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
				var target = failure.equals("timeout")
						? new ProcessedLogPublisher(rabbit, mapper, Duration.ofMillis(100)) : publisher;
				try {
					assertUnavailable(assertThrows(ProcessingException.class, () -> target.publish(log("ERROR", "{}"))));
					if (failure.equals("timeout")) {
						assertTrue(sent.size() >= 1 && sent.size() <= 2);
					} else {
						assertEquals(route.equals("processed.log") ? 1 : 2, sent.size());
					}
					assertEquals(failure.equals("interrupt"), Thread.currentThread().isInterrupted());
					if (route.equals("critical.log")) {
						assertTrue(sent.getFirst().correlation().getFuture().join().ack());
					}
				} finally {
					Thread.interrupted();
					sent.forEach(publication -> publication.correlation().getFuture()
							.complete(new CorrelationData.Confirm(false, null)));
				}
			}
		}
	}

	@Test
	void callerRetryPreservesBodiesAndLogicalIdsWithIndependentTransportCorrelations() {
		var failCriticalOnce = new AtomicBoolean(true);
		doAnswer(call -> {
			var publication = new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			sent.add(publication);
			boolean ack = !publication.key().equals("critical.log") || !failCriticalOnce.getAndSet(false);
			publication.correlation().getFuture().complete(new CorrelationData.Confirm(ack, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		NormalizedLog log = log("CRITICAL", "{}");
		assertUnavailable(assertThrows(ProcessingException.class, () -> publisher.publish(log)));
		assertDoesNotThrow(() -> publisher.publish(log));
		assertEquals(4, sent.size());
		assertArrayEquals(sent.get(0).message().getBody(), sent.get(2).message().getBody());
		assertArrayEquals(sent.get(1).message().getBody(), sent.get(3).message().getBody());
		assertEquals(4, sent.stream().map(send -> send.correlation().getId()).distinct().count());
		for (Sent publication : sent) {
			assertEquals(log.eventId().toString(), publication.message().getMessageProperties().getMessageId());
		}
	}

	@Test
	void concurrentDuplicateIdsHaveIndependentReturnsAndConfirmations() throws Exception {
		var sends = new LinkedBlockingQueue<Sent>();
		doAnswer(call -> {
			sends.add(new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		NormalizedLog log = log("WARN", "{}");
		var executor = Executors.newFixedThreadPool(2);
		try {
			var attempts = List.of(executor.submit(() -> publisher.publish(log)), executor.submit(() -> publisher.publish(log)));
			Sent first = sends.poll(2, TimeUnit.SECONDS);
			Sent second = sends.poll(2, TimeUnit.SECONDS);
			assertNotNull(first);
			assertNotNull(second);
			assertNotEquals(first.correlation().getId(), second.correlation().getId());
			assertEquals(first.message().getMessageProperties().getMessageId(), second.message().getMessageProperties().getMessageId());
			first.correlation().setReturned(new ReturnedMessage(first.message(), 312, "private", first.exchange(), first.key()));
			first.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			second.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			int failures = 0;
			for (var attempt : attempts) {
				try {
					attempt.get(2, TimeUnit.SECONDS);
				} catch (ExecutionException exception) {
					assertUnavailable(assertInstanceOf(ProcessingException.class, exception.getCause()));
					failures++;
				}
			}
			assertEquals(1, failures);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void invalidInternalMetadataFailsBeforeAnyPublicationWithoutExposingContent() {
		for (String metadata : List.of("null", "[]", "123", "\"private text\"", "{private text}", "{} {}")) {
			var failure = assertThrows(IllegalStateException.class, () -> publisher.publish(log("ERROR", metadata)));
			assertEquals("Normalized metadata must be a JSON object", failure.getMessage());
			assertNull(failure.getCause());
		}
		assertThrows(NullPointerException.class, () -> publisher.publish(null));
		verifyNoInteractions(rabbit);
	}

	@Test
	void unexpectedSerializationFailureIsNotRelabeledAsBrokerFailure() {
		var brokenMapper = spy(mapper);
		var target = new ProcessedLogPublisher(rabbit, brokenMapper, Duration.ofSeconds(5));
		var failure = new IllegalStateException("internal failure");
		doThrow(failure).when(brokenMapper).writeValueAsBytes(any());
		assertSame(failure, assertThrows(IllegalStateException.class, () -> target.publish(log("ERROR", "{}"))));
		verifyNoInteractions(rabbit);
	}

	@Test
	void windowCountsBothRoutesAndDoesNotOpenNextWindowBeforeEveryConfirmation() throws Exception {
		var sends = pendingSends();
		var records = java.util.stream.IntStream.range(0, 33).mapToObj(index -> log("ERROR", "{}")).toList();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var work = executor.submit(() -> publisher.publishBatch(records));
			var firstWindow = new ArrayList<Sent>();
			for (int index = 0; index < 64; index++) {
				Sent publication = sends.poll(2, TimeUnit.SECONDS);
				assertNotNull(publication);
				assertContract(records.get(index / 2), publication, index % 2 == 1);
				firstWindow.add(publication);
			}
			assertTrue(sends.isEmpty());
			for (int index = 63; index > 0; index--) {
				firstWindow.get(index).correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			}
			assertFalse(work.isDone());
			assertTrue(sends.isEmpty());
			firstWindow.getFirst().correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			for (int index = 0; index < 2; index++) {
				Sent publication = sends.poll(2, TimeUnit.SECONDS);
				assertNotNull(publication);
				publication.correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
			}
			work.get(2, TimeUnit.SECONDS);
		}
	}

	@Test
	void timeoutRetainsAllNativeSlotsAcrossCallsUntilLateTerminalConfirms() throws Exception {
		var sends = pendingSends();
		var target = new ProcessedLogPublisher(rabbit, mapper, Duration.ofMillis(100));
		var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("INFO", "{}")).toList();
		assertUnavailable(assertThrows(ProcessingException.class, () -> target.publishBatch(records)));
		assertEquals(64, sends.size());
		assertTrue(sends.stream().noneMatch(send -> send.correlation().getFuture().isDone()));
		assertUnavailable(assertThrows(ProcessingException.class, () -> target.publish(log("INFO", "{}"))));
		assertEquals(64, sends.size());
		sends.forEach(send -> send.correlation().getFuture().complete(new CorrelationData.Confirm(false, null)));
		confirmedSends();
		assertDoesNotThrow(() -> target.publishBatch(records));
		assertEquals(64, sent.size());
	}

	@Test
	void concurrentCallsShareTheSameNativePendingBound() throws Exception {
		var sends = pendingSends();
		var target = new ProcessedLogPublisher(rabbit, mapper, Duration.ofSeconds(1));
		var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("WARN", "{}")).toList();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> target.publishBatch(records));
			var window = new ArrayList<Sent>();
			for (int index = 0; index < 64; index++) {
				window.add(sends.poll(2, TimeUnit.SECONDS));
				assertNotNull(window.getLast());
			}
			var second = executor.submit(() -> target.publishBatch(records));
			assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
			assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
			assertTrue(sends.isEmpty());
			window.forEach(send -> send.correlation().getFuture().complete(new CorrelationData.Confirm(true, null)));
		}
	}

	@Test
	void middleWindowNackOrReturnRejectsBatchWithoutSendingNextWindow() throws Exception {
		for (boolean returned : List.of(false, true)) {
			var sends = pendingSends();
			var records = java.util.stream.IntStream.range(0, 65).mapToObj(index -> log("INFO", "{}")).toList();
			try (var executor = Executors.newSingleThreadExecutor()) {
				var work = executor.submit(() -> publisher.publishBatch(records));
				var window = new ArrayList<Sent>();
				for (int index = 0; index < 64; index++) {
					window.add(sends.poll(2, TimeUnit.SECONDS));
					assertNotNull(window.getLast());
				}
				Sent failed = window.get(31);
				if (returned) {
					failed.correlation().setReturned(new ReturnedMessage(failed.message(), 312, "private", failed.exchange(), failed.key()));
				}
				window.forEach(send -> send.correlation().getFuture()
						.complete(new CorrelationData.Confirm(returned || send != failed, null)));
				assertUnavailable(assertInstanceOf(ProcessingException.class,
						assertThrows(ExecutionException.class, () -> work.get(2, TimeUnit.SECONDS)).getCause()));
				assertTrue(sends.isEmpty());
			}
		}
	}

	@Test
	void failedSendBeforeNativeRegistrationRecoversWithoutLeakingReservedSlots() {
		var fail = new AtomicBoolean(true);
		doAnswer(call -> {
			if (fail.getAndSet(false)) {
				throw new AmqpConnectException(new ConnectException());
			}
			CorrelationData correlation = call.getArgument(3);
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("INFO", "{}")).toList();
		assertUnavailable(assertThrows(ProcessingException.class, () -> publisher.publishBatch(records)));
		assertDoesNotThrow(() -> publisher.publishBatch(records));
	}

	@Test
	void middleWindowSendFailureStopsThePrefixAndRetryKeepsStableBodiesWithFreshCorrelations() {
		doAnswer(call -> {
			var publication = new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
			sent.add(publication);
			if (sent.size() == 17) {
				throw new AmqpConnectException(new ConnectException());
			}
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		var records = java.util.stream.IntStream.range(0, 65).mapToObj(index -> log("INFO", "{}")).toList();
		assertUnavailable(assertThrows(ProcessingException.class, () -> publisher.publishBatch(records)));
		assertEquals(17, sent.size());
		assertTrue(sent.stream().noneMatch(send -> send.correlation().getFuture().isDone()));
		for (int index = 0; index < 16; index++) {
			sent.get(index).correlation().getFuture().complete(new CorrelationData.Confirm(true, null));
		}
		confirmedSends();
		assertDoesNotThrow(() -> publisher.publishBatch(records));
		assertEquals(82, sent.size());
		for (int index = 0; index < 17; index++) {
			assertArrayEquals(sent.get(index).message().getBody(), sent.get(index + 17).message().getBody());
		}
		assertEquals(82, sent.stream().map(send -> send.correlation().getId()).distinct().count());
		assertTrue(sent.get(16).correlation().getFuture().isCompletedExceptionally());
	}

	@Test
	void ambiguousSendKeepsItsSlotUntilNativeChannelStateHasBeenCleaned() {
		var channel = mock(PublisherCallbackChannel.class);
		when(channel.getPendingConfirmsCount()).thenReturn(1);
		doAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(channel))
				.when(rabbit).execute(any(ChannelCallback.class));
		var failedCorrelation = new AtomicReference<CorrelationData>();
		doAnswer(call -> {
			failedCorrelation.set(call.getArgument(3));
			throw new AmqpConnectException(new ConnectException());
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		var target = new ProcessedLogPublisher(rabbit, mapper, Duration.ofMillis(100));
		var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("INFO", "{}")).toList();
		assertUnavailable(assertThrows(ProcessingException.class, () -> target.publishBatch(records)));
		CorrelationData pending = failedCorrelation.get();
		assertFalse(pending.getFuture().isDone());
		assertUnavailable(assertThrows(ProcessingException.class, () -> target.publishBatch(records)));
		assertSame(pending, failedCorrelation.get());
		assertFalse(pending.getFuture().isDone());
		when(channel.getPendingConfirmsCount()).thenReturn(0);
		confirmedSends();
		assertDoesNotThrow(() -> target.publishBatch(records));
		assertTrue(pending.getFuture().isCompletedExceptionally());
		assertEquals(64, sent.size());
	}

	@Test
	void failureOpeningSpringScopeReleasesOnlyUnsentReservations() {
		doThrow(new AmqpConnectException(new ConnectException())).when(rabbit)
				.invoke(any(RabbitOperations.OperationsCallback.class));
		var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("INFO", "{}")).toList();
		assertUnavailable(assertThrows(ProcessingException.class, () -> publisher.publishBatch(records)));
		assertTrue(sent.isEmpty());
		confirmedSends();
		assertDoesNotThrow(() -> publisher.publishBatch(records));
		assertEquals(64, sent.size());
	}

	@Test
	void nativeSpringChannelRetirementCleansTimedOutAndAmbiguousSendConfirmations() throws Exception {
		for (String failure : List.of("timeout", "send", "connection")) {
			var client = mock(com.rabbitmq.client.ConnectionFactory.class);
			var connection = mock(com.rabbitmq.client.Connection.class);
			var old = mock(Channel.class);
			var replacement = mock(Channel.class);
			var oldSequence = new AtomicLong(1);
			var newSequence = new AtomicLong(1);
			var newConfirmListener = new AtomicReference<ConfirmListener>();
			var shutdownListener = new AtomicReference<ShutdownListener>();
			var retired = new CountDownLatch(1);
			when(client.newConnection(nullable(java.util.concurrent.ExecutorService.class), any(String.class))).thenReturn(connection);
			when(connection.isOpen()).thenReturn(true);
			when(connection.createChannel()).thenReturn(old, replacement);
			when(old.isOpen()).thenReturn(true);
			when(replacement.isOpen()).thenReturn(true);
			when(old.getNextPublishSeqNo()).thenAnswer(call -> oldSequence.get());
			when(replacement.getNextPublishSeqNo()).thenAnswer(call -> newSequence.get());
			doAnswer(call -> { shutdownListener.set(call.getArgument(0)); return null; })
					.when(old).addShutdownListener(any(ShutdownListener.class));
			doAnswer(call -> {
				oldSequence.incrementAndGet();
				if (failure.equals("connection")) {
					shutdownListener.get().shutdownCompleted(new ShutdownSignalException(true, false, null, connection));
				}
				if (failure.equals("send")) {
					throw new IOException("ambiguous transport failure");
				}
				return null;
			}).when(old).basicPublish(any(String.class), any(String.class), anyBoolean(), any(AMQP.BasicProperties.class), any(byte[].class));
			doAnswer(call -> {
				newConfirmListener.set(call.getArgument(0));
				return null;
			}).when(replacement).addConfirmListener(any(ConfirmListener.class));
			doAnswer(call -> {
				newConfirmListener.get().handleAck(newSequence.getAndIncrement(), false);
				return null;
			}).when(replacement).basicPublish(any(String.class), any(String.class), anyBoolean(), any(AMQP.BasicProperties.class), any(byte[].class));
			doAnswer(call -> { retired.countDown(); return null; }).when(old).close();
			var factory = new CachingConnectionFactory(client);
			factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
			factory.setPublisherReturns(true);
			factory.setCloseTimeout(100);
			try {
				var template = new RabbitTemplate(factory);
				template.setMandatory(true);
				var target = new ProcessedLogPublisher(template, mapper, Duration.ofMillis(300));
				var records = java.util.stream.IntStream.range(0, 64).mapToObj(index -> log("INFO", "{}")).toList();
				assertUnavailable(assertThrows(ProcessingException.class, () -> target.publishBatch(records)));
				assertTrue(retired.await(2, TimeUnit.SECONDS));
				// A successful full-size retry requires every native slot from the retired channel to be reclaimed.
				assertDoesNotThrow(() -> target.publishBatch(records));
				assertEquals(0, template.getUnconfirmedCount());
				verify(old).close();
			} finally {
				factory.destroy();
			}
		}
	}

	private LinkedBlockingQueue<Sent> pendingSends() {
		var sends = new LinkedBlockingQueue<Sent>();
		doAnswer(call -> {
			sends.add(new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		return sends;
	}

	@Test
	void topologyDeclaresOnlyTheTwoDurableRoutesWithoutExtraArguments() {
		var declarations = new ProcessingRabbitConfiguration().downstreamLogTopology();
		assertEquals(6, declarations.getDeclarables().size());
		var exchanges = declarations.getDeclarablesByType(DirectExchange.class);
		assertEquals(Set.of("processed.exchange", "critical.exchange"),
				exchanges.stream().map(DirectExchange::getName).collect(java.util.stream.Collectors.toSet()));
		for (var exchange : exchanges) {
			assertTrue(exchange.isDurable());
			assertFalse(exchange.isAutoDelete());
			assertFalse(exchange.isInternal());
		}
		var queues = declarations.getDeclarablesByType(Queue.class);
		assertEquals(Set.of("realtime.queue", "alert.queue"),
				queues.stream().map(Queue::getName).collect(java.util.stream.Collectors.toSet()));
		for (var queue : queues) {
			assertTrue(queue.isDurable());
			assertFalse(queue.isExclusive());
			assertFalse(queue.isAutoDelete());
			assertTrue(queue.getArguments() == null || queue.getArguments().isEmpty());
		}
		var bindings = declarations.getDeclarablesByType(Binding.class);
		assertEquals(Set.of("processed.exchange/processed.log/realtime.queue", "critical.exchange/critical.log/alert.queue"),
				bindings.stream().map(binding -> binding.getExchange() + "/" + binding.getRoutingKey() + "/" + binding.getDestination())
						.collect(java.util.stream.Collectors.toSet()));
		bindings.forEach(binding -> assertTrue(binding.getArguments() == null || binding.getArguments().isEmpty()));
	}

	@Test
	void actualYamlDefaultsReuseMandatoryConfirmsAndDoNotSendAtStartup() throws Exception {
		try (var context = context(null)) {
			context.refresh();
			assertNotNull(context.getBean(ProcessedLogPublisher.class));
			assertEquals("5s", context.getEnvironment().getProperty("vanioak.processing.publish-confirm-timeout"));
			assertEquals("correlated", context.getEnvironment().getProperty("spring.rabbitmq.publisher-confirm-type"));
			assertEquals("true", context.getEnvironment().getProperty("spring.rabbitmq.publisher-returns"));
			assertEquals("true", context.getEnvironment().getProperty("spring.rabbitmq.template.mandatory"));
			verify(rabbit, never()).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
		}
	}

	@Test
	void invalidTimeoutFailsConfigurationAndEnvironmentBindingIsUsed() throws Exception {
		for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1)}) {
			assertThrows(IllegalArgumentException.class, () -> new ProcessedLogPublisher(rabbit, mapper, timeout));
		}
		try (var context = context("0ms")) {
			assertThrows(BeanCreationException.class, context::refresh);
		}
		try (var context = context("1ms")) {
			context.refresh();
			assertEquals("1ms", context.getEnvironment().getProperty("vanioak.processing.publish-confirm-timeout"));
			assertNotNull(context.getBean(ProcessedLogPublisher.class));
		}
	}

	private AnnotationConfigApplicationContext context(String timeout) throws Exception {
		var context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		if (timeout != null) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
					Map.of("PROCESSING_PUBLISH_CONFIRM_TIMEOUT", timeout)));
		}
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
				.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
		context.registerBean("conversionService", ApplicationConversionService.class, () -> new ApplicationConversionService());
		context.registerBean(RabbitTemplate.class, () -> rabbit);
		context.registerBean(ObjectMapper.class, () -> mapper);
		context.register(ProcessedLogPublisher.class, ProcessingRabbitConfiguration.class);
		return context;
	}

	private void assertContract(NormalizedLog log, Sent publication, boolean critical) {
		String type = critical ? "critical.log" : "processed.log";
		assertEquals(critical ? "critical.exchange" : "processed.exchange", publication.exchange());
		assertEquals(type, publication.key());
		assertEquals(MessageDeliveryMode.PERSISTENT, publication.message().getMessageProperties().getDeliveryMode());
		assertEquals("application/json", publication.message().getMessageProperties().getContentType());
		assertEquals(log.eventId().toString(), publication.message().getMessageProperties().getMessageId());
		var reader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
		JsonNode event = reader.readTree(publication.message().getBody());
		assertEquals(Set.of("event_id", "event_type", "occurred_at", "producer", "schema_version", "payload"), event.propertyNames());
		assertEquals(log.eventId().toString(), event.get("event_id").asString());
		assertEquals(type, event.get("event_type").asString());
		assertEquals(log.processedAt().toString(), event.get("occurred_at").asString());
		assertEquals("processing", event.get("producer").asString());
		assertTrue(event.get("schema_version").isIntegralNumber());
		assertEquals(1, event.get("schema_version").asInt());
		JsonNode payload = event.get("payload");
		var keys = new HashSet<>(Set.of("event_id", "application_id", "environment_id", "level", "message", "timestamp", "trace_id", "received_at"));
		if (!critical) {
			keys.addAll(Set.of("host_ip", "metadata", "processed_at"));
			assertEquals(log.hostIp(), payload.get("host_ip").asString());
			assertTrue(payload.get("metadata").isObject());
			assertEquals(reader.readTree(log.metadataJson()), payload.get("metadata"));
			assertEquals(log.processedAt().toString(), payload.get("processed_at").asString());
		}
		assertEquals(keys, payload.propertyNames());
		assertEquals(log.eventId().toString(), payload.get("event_id").asString());
		assertEquals(log.applicationId().toString(), payload.get("application_id").asString());
		assertEquals(log.environmentId().toString(), payload.get("environment_id").asString());
		assertEquals(log.level(), payload.get("level").asString());
		assertEquals(log.message(), payload.get("message").asString());
		assertEquals(log.timestamp().toString(), payload.get("timestamp").asString());
		assertEquals(log.traceId(), payload.get("trace_id").asString());
		assertEquals(log.receivedAt().toString(), payload.get("received_at").asString());
	}

	private void assertUnavailable(ProcessingException failure) {
		assertEquals(ErrorCode.PUBLICATION_UNAVAILABLE, failure.getErrorCode());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getErrorCode().getStatus());
		assertEquals("Log publication unavailable", failure.getMessage());
		assertNull(failure.getCause());
	}

	private NormalizedLog log(String level, String metadata) {
		return new NormalizedLog(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", level,
				"Lỗi \"quoted\"\nnext line", TIMESTAMP, "trace\n\"id", metadata, RECEIVED, PROCESSED);
	}

	private record Sent(String exchange, String key, Message message, CorrelationData correlation) { }

	private static RawLogNormalizer normalizer(ObjectMapper mapper, Clock clock) {
		try {
			return BeanUtils.instantiateClass(
					RawLogNormalizer.class.getDeclaredConstructor(ObjectMapper.class, Clock.class), mapper, clock);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(exception);
		}
	}
}
