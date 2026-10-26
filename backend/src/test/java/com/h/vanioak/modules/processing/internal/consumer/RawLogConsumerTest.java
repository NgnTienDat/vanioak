package com.h.vanioak.modules.processing.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.config.ProcessingConsumerConfiguration;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;
import com.h.vanioak.modules.processing.internal.persistence.ClickHouseLogWriter;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;
import com.h.vanioak.modules.processing.internal.publisher.ProcessingFailurePublisher;
import com.rabbitmq.client.Channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RawLogConsumerTest {

	private static final Instant NOW = Instant.parse("2026-10-10T01:02:03.123456789Z");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final RawLogNormalizer normalizer = normalizer(mapper, Clock.fixed(NOW, ZoneOffset.UTC));
	private final ProcessingBatchWriter batches = mock(ProcessingBatchWriter.class);
	private final ProcessedLogPublisher publisher = mock(ProcessedLogPublisher.class);
	private final ProcessingFailurePublisher failures = mock(ProcessingFailurePublisher.class);
	private final ManualExecutor work = new ManualExecutor();
	private final ScheduledExecutorService cooldowns = mock(ScheduledExecutorService.class);
	private final List<Runnable> cooldownTasks = new ArrayList<>();
	private final List<CompletableFuture<Void>> persistence = new ArrayList<>();
	private final List<List<NormalizedLog>> submissions = new ArrayList<>();
	private RawLogConsumer consumer;
	private ProcessingBatchWriter realBatches;
	private Level previousAdapterLevel;

	@BeforeEach
	void setUp() {
		var adapterLogger = (Logger) LoggerFactory.getLogger(MessagingMessageListenerAdapter.class);
		previousAdapterLevel = adapterLogger.getLevel();
		adapterLogger.setLevel(Level.OFF);
		when(failures.cooldown()).thenReturn(Duration.ofSeconds(5));
		when(cooldowns.schedule(any(Runnable.class), eq(5000L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
			cooldownTasks.add(call.getArgument(0));
			return mock(ScheduledFuture.class);
		});
		when(batches.submit(anyList())).thenAnswer(call -> {
			submissions.add(List.copyOf(call.getArgument(0)));
			var ticket = new CompletableFuture<Void>();
			persistence.add(ticket);
			return ticket;
		});
		consumer = new RawLogConsumer(normalizer, batches, publisher, failures, 16, work, cooldowns);
	}

	@AfterEach
	void close() {
		consumer.close();
		if (realBatches != null) realBatches.close();
		Thread.interrupted();
		((Logger) LoggerFactory.getLogger(MessagingMessageListenerAdapter.class)).setLevel(previousAdapterLevel);
	}

	@Test
	void waitsForWholeSubmissionAndAllRequiredPublicationsWithStableScopeAndIds() {
		Message first = raw(2, "INFO");
		Message second = raw(2, "ERROR");
		var firstResult = consumer.consume(first);
		var secondResult = consumer.consume(second);
		assertFalse(firstResult.isDone());
		assertFalse(secondResult.isDone());
		verifyNoInteractions(publisher);
		assertEquals(normalizer.normalize(first.getBody()), submissions.getFirst());
		assertEquals(normalizer.normalize(second.getBody()), submissions.getLast());
		persistence.getFirst().complete(null);
		assertFalse(firstResult.isDone());
		work.runAll();
		assertTrue(firstResult.isDone());
		assertFalse(firstResult.isCompletedExceptionally());
		assertFalse(secondResult.isDone());
		verify(publisher).publishBatch(submissions.getFirst());
		verify(failures, never()).retry(any());
		verify(failures, never()).deadLetter(any(), any());
	}

	@Test
	void duplicateDeliveriesKeepIndependentPersistenceAndRetryAccounting() {
		Message raw = raw(1, "WARN");
		var first = consumer.consume(raw);
		var second = consumer.consume(raw);
		assertEquals(submissions.getFirst(), submissions.getLast());
		persistence.getFirst().complete(null);
		persistence.getLast().completeExceptionally(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE));
		work.runAll();
		assertFalse(first.isCompletedExceptionally());
		assertFalse(second.isCompletedExceptionally());
		verify(publisher).publishBatch(submissions.getFirst());
		verify(failures).retry(raw);
		verify(failures, never()).deadLetter(any(), any());
	}

	@Test
	void invalidLogRejectsWholeBatchBeforePersistenceAndInvalidCounterSkipsNormalization() {
		Message source = raw(2, "INFO");
		var root = mapper.readTree(source.getBody());
		((ObjectNode) root.get("payload").get("logs").get(1)).put("level", "invalid");
		Message invalid = new Message(mapper.writeValueAsBytes(root), source.getMessageProperties());
		var result = consumer.consume(invalid);
		assertFalse(result.isDone());
		verifyNoInteractions(batches, publisher);
		work.runAll();
		verify(failures).deadLetter(invalid, ProcessingFailurePublisher.INVALID_BATCH);
		assertFalse(result.isCompletedExceptionally());
		Message badCounter = raw(1, "INFO");
		when(failures.retryCount(badCounter)).thenReturn(-1);
		var counterResult = consumer.consume(badCounter);
		work.runAll();
		verify(failures).deadLetter(badCounter, ProcessingFailurePublisher.INVALID_RETRY);
		verifyNoInteractions(batches, publisher);
		assertFalse(counterResult.isCompletedExceptionally());
	}

	@Test
	void unexpectedListenerErrorsUseRetryAndNeverClaimInvalidUserData() {
		var broken = mock(RawLogNormalizer.class);
		when(broken.normalize(any())).thenThrow(new IllegalStateException("secret body"));
		consumer.close();
		consumer = new RawLogConsumer(broken, batches, publisher, failures, 16, new ManualExecutor(), cooldowns);
		var executor = (ManualExecutor) ReflectionTestUtils.getField(consumer, "publications");
		Message raw = raw(1, "INFO");
		var result = consumer.consume(raw);
		executor.runAll();
		assertFalse(result.isCompletedExceptionally());
		verify(failures).retry(raw);
		verify(failures, never()).deadLetter(any(), any());
		verifyNoInteractions(batches, publisher);
	}

	@Test
	void partialDownstreamPublicationRetriesWholeOriginal() {
		Message raw = raw(3, "ERROR");
		var result = consumer.consume(raw);
		doThrow(new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE))
				.when(publisher).publishBatch(submissions.getFirst());
		persistence.getFirst().complete(null);
		work.runAll();
		verify(publisher).publishBatch(submissions.getFirst());
		verify(failures).retry(raw);
		assertTrue(result.isDone());
	}

	@Test
	void nativeAsyncAdapterAcksIndividuallyAfterAccountingAndHandlesOutOfOrderCompletion() throws Exception {
		var adapter = adapter();
		Channel channel = mock(Channel.class);
		Message first = tagged(raw(1, "INFO"), 41);
		Message second = tagged(raw(1, "CRITICAL"), 42);
		adapter.onMessage(first, channel);
		adapter.onMessage(second, channel);
		verifyNoInteractions(channel);
		persistence.get(1).complete(null);
		work.runAll();
		verify(channel).basicAck(42, false);
		verify(channel, never()).basicAck(41, false);
		persistence.get(0).complete(null);
		work.runAll();
		verify(channel).basicAck(41, false);
		verify(channel, never()).basicAck(anyLong(), eq(true));
		verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
	}

	@Test
	void nativeAdapterOnlyRejectsAfterUnconfirmedHandoffCooldownAndLogsNoBody() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(MessagingMessageListenerAdapter.class);
		Level previous = logger.getLevel();
		var captured = new ListAppender<ILoggingEvent>();
		captured.start();
		logger.addAppender(captured);
		logger.setLevel(Level.OFF);
		try {
			Channel channel = mock(Channel.class);
			Message raw = tagged(raw(1, "INFO"), 7);
			doThrow(new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE)).when(failures).retry(raw);
			adapter().onMessage(raw, channel);
			persistence.getFirst().completeExceptionally(new IllegalStateException("private body"));
			work.runAll();
			verifyNoInteractions(channel);
			assertEquals(1, cooldownTasks.size());
			cooldownTasks.getFirst().run();
			verify(channel).basicReject(7, true);
			verify(channel, never()).basicAck(anyLong(), eq(false));
			assertTrue(captured.list.isEmpty());
		} finally {
			logger.setLevel(previous);
			logger.detachAppender(captured);
			captured.stop();
		}
	}

	@Test
	void oldChannelCompletionCannotAckReplacementChannelWithReusedDeliveryTag() throws Exception {
		Channel oldChannel = mock(Channel.class);
		Channel replacement = mock(Channel.class);
		doThrow(new IOException("channel closed")).when(oldChannel).basicAck(1, false);
		var adapter = adapter();
		adapter.onMessage(tagged(raw(1, "INFO"), 1), oldChannel);
		adapter.onMessage(tagged(raw(1, "INFO"), 1), replacement);
		persistence.get(0).complete(null);
		work.runAll();
		verify(oldChannel).basicAck(1, false);
		verifyNoInteractions(replacement);
		persistence.get(1).complete(null);
		work.runAll();
		verify(replacement).basicAck(1, false);
	}

	@Test
	void shutdownRequeuesUnresolvedNativeDeliveryAndLatePersistenceDoesNotPublishOrAck() throws Exception {
		Channel channel = mock(Channel.class);
		adapter().onMessage(tagged(raw(1, "ERROR"), 11), channel);
		consumer.close();
		verify(channel).basicReject(11, true);
		persistence.getFirst().complete(null);
		work.runAll();
		verifyNoInteractions(publisher);
		verify(channel, never()).basicAck(anyLong(), eq(false));
		assertTrue(work.isShutdown());
		verify(cooldowns).shutdownNow();
		var later = consumer.consume(raw(1, "INFO"));
		assertRequeue(later);
	}

	@Test
	void executorRejectionAndAdmissionInterruptionRemainUnacknowledged() {
		Message raw = raw(1, "INFO");
		var result = consumer.consume(raw);
		work.shutdownNow();
		persistence.getFirst().complete(null);
		assertRequeue(result);
		Thread.currentThread().interrupt();
		try {
			assertRequeue(consumer.consume(raw));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
		verifyNoInteractions(publisher);
	}

	@Test
	void smallBatchesAtPrefetchCeilingFlushOnTimerAndReleaseAdmission() throws Exception {
		var inserted = mock(ClickHouseLogWriter.class);
		var nanos = new AtomicLong();
		var deadlines = new ArrayList<Runnable>();
		useRealBatchWriter(inserted, nanos, deadlines);
		var results = new ArrayList<CompletableFuture<Void>>();
		for (int index = 0; index < 16; index++) results.add(consumer.consume(raw(1, "INFO")));
		assertTrue(results.stream().noneMatch(CompletableFuture::isDone));
		verifyNoInteractions(inserted, publisher);
		var semaphore = (Semaphore) ReflectionTestUtils.getField(consumer, "admission");
		assertEquals(0, semaphore.availablePermits());
		try (var listener = Executors.newSingleThreadExecutor()) {
			var seventeenth = listener.submit(() -> consumer.consume(raw(1, "INFO")));
			awaitQueuedAdmission(semaphore);
			assertFalse(seventeenth.isDone());
			nanos.set(TimeUnit.SECONDS.toNanos(2));
			deadlines.getLast().run();
			org.mockito.ArgumentCaptor<List<NormalizedLog>> rows = org.mockito.ArgumentCaptor.captor();
			verify(inserted).write(rows.capture());
			assertEquals(16, rows.getValue().size());
			work.runAll();
			assertTrue(results.stream().allMatch(CompletableFuture::isDone));
			assertFalse(seventeenth.get(2, TimeUnit.SECONDS).isDone());
		}
	}

	@Test
	void splitSubmissionWaitsForLastChunkAndFailedChunkRetriesTheWholeBatch() {
		var inserted = mock(ClickHouseLogWriter.class);
		var nanos = new AtomicLong();
		var deadlines = new ArrayList<Runnable>();
		useRealBatchWriter(inserted, nanos, deadlines);
		Message raw = raw(1001, "INFO");
		var result = consumer.consume(raw);
		assertFalse(result.isDone());
		verifyNoInteractions(publisher);
		doThrow(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE)).when(inserted).write(anyList());
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		deadlines.getLast().run();
		work.runAll();
		verify(failures).retry(raw);
		assertTrue(result.isDone());
		verifyNoInteractions(publisher);
	}

	@Test
	void sharedInsertFailureTransfersEachAffectedRawDeliveryIndependently() {
		var inserted = mock(ClickHouseLogWriter.class);
		doThrow(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE)).when(inserted).write(anyList());
		useRealBatchWriter(inserted, new AtomicLong(), new ArrayList<>());
		Message first = raw(600, "INFO");
		Message second = raw(600, "WARN");
		var a = consumer.consume(first);
		var b = consumer.consume(second);
		work.runAll();
		assertTrue(a.isDone());
		assertTrue(b.isDone());
		verify(failures).retry(first);
		verify(failures).retry(second);
		verifyNoInteractions(publisher);
	}

	@Test
	void cooldownTimerRunsWhileSynchronousPublicationWorkerIsBlocked() throws Exception {
		consumer.close();
		var publishing = Executors.newSingleThreadExecutor();
		var timer = Executors.newSingleThreadScheduledExecutor();
		var transferFailed = new CountDownLatch(1);
		var publishBlocked = new CountDownLatch(1);
		var unblock = new CountDownLatch(1);
		var deadline = new CompletableFuture<Runnable>();
		var capturedTimer = mock(ScheduledExecutorService.class);
		when(capturedTimer.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
			deadline.complete(call.getArgument(0));
			return mock(ScheduledFuture.class);
		});
		consumer = new RawLogConsumer(normalizer, batches, publisher, failures, 16, publishing, capturedTimer);
		Message invalid = new Message(new byte[] {1}, new MessageProperties());
		doAnswer(call -> {
			transferFailed.countDown();
			throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
		}).when(failures).deadLetter(invalid, ProcessingFailurePublisher.INVALID_BATCH);
		doAnswer(call -> {
			publishBlocked.countDown();
			assertTrue(unblock.await(2, TimeUnit.SECONDS));
			return null;
		}).when(publisher).publishBatch(any());
		try {
			var failedHandoff = consumer.consume(invalid);
			assertTrue(transferFailed.await(2, TimeUnit.SECONDS));
			Runnable cooldown = deadline.get(2, TimeUnit.SECONDS);
			var publishingResult = consumer.consume(raw(1, "INFO"));
			persistence.getFirst().complete(null);
			assertTrue(publishBlocked.await(2, TimeUnit.SECONDS));
			timer.schedule(cooldown, 0, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
			assertRequeue(failedHandoff);
			assertFalse(publishingResult.isDone());
			unblock.countDown();
			publishingResult.get(2, TimeUnit.SECONDS);
		} finally {
			unblock.countDown();
			timer.shutdownNow();
		}
	}

	@Test
	void configurationIsDisabledByDefaultAndEnabledFactoryIsScopedAndSafe() throws Exception {
		try (var context = context(Map.of())) {
			context.refresh();
			assertTrue(context.getBeansOfType(RawLogConsumer.class).isEmpty());
			assertEquals("false", context.getEnvironment().getProperty("vanioak.processing.consumer-enabled"));
			assertEquals("OFF", context.getEnvironment().getProperty(
					"logging.level.org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter"));
		}
		try (var context = context(Map.of("PROCESSING_CONSUMER_ENABLED", "true"))) {
			context.refresh();
			var registry = context.getBean(RabbitListenerEndpointRegistry.class);
			var container = registry.getListenerContainer("processingRawConsumer");
			assertNotNull(container);
			assertFalse(container.isRunning());
			assertEquals(AcknowledgeMode.MANUAL, ReflectionTestUtils.getField(container, "acknowledgeMode"));
			assertEquals(16, ReflectionTestUtils.getField(container, "prefetchCount"));
			assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
			assertEquals(1, ReflectionTestUtils.getField(container, "maxConcurrentConsumers"));
			assertEquals(true, ReflectionTestUtils.getField(container, "forceStop"));
			var errorHandler = (org.springframework.util.ErrorHandler) ReflectionTestUtils.getField(container, "errorHandler");
			var failure = assertThrows(ImmediateRequeueAmqpException.class,
					() -> errorHandler.handleError(new IllegalStateException("private body")));
			assertNull(failure.getCause());
			assertEquals("Raw listener delivery remains unaccounted", failure.getMessage());
		}
		verifyNoInteractions(publisher);
	}

	@Test
	void configurationRejectsInvalidPrefetchAndRetryPolicyAndBindsOverrides() throws Exception {
		for (Map<String, Object> values : List.of(Map.<String, Object>of("PROCESSING_CONSUMER_PREFETCH", "0"),
				Map.<String, Object>of("PROCESSING_RETRY_MAX_ATTEMPTS", "2"),
				Map.<String, Object>of("PROCESSING_RETRY_DELAYS", "5s,0s,120s"))) {
			var settings = new java.util.HashMap<>(values);
			settings.put("PROCESSING_CONSUMER_ENABLED", "true");
			try (var context = context(settings)) {
				assertThrows(BeanCreationException.class, context::refresh);
			}
		}
		try (var context = context(Map.of("PROCESSING_CONSUMER_ENABLED", "true",
				"PROCESSING_CONSUMER_PREFETCH", "32", "PROCESSING_RETRY_MAX_ATTEMPTS", "0", "PROCESSING_RETRY_DELAYS", ""))) {
			context.refresh();
			var container = context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("processingRawConsumer");
			assertEquals(32, ReflectionTestUtils.getField(container, "prefetchCount"));
		}
	}

	@Test
	void topologyAddsOnlyApprovedQuorumRetryAndDurableDlqRoute() {
		var topology = (Declarables) ReflectionTestUtils.invokeMethod(
				new ProcessingConsumerConfiguration(), "processingFailureTopology");
		assertEquals(4, topology.getDeclarables().size());
		var queues = topology.getDeclarablesByType(Queue.class);
		assertEquals(Set.of("raw.retry.queue", "dead-letter.queue"),
				queues.stream().map(Queue::getName).collect(java.util.stream.Collectors.toSet()));
		queues.forEach(queue -> {
			assertTrue(queue.isDurable());
			assertFalse(queue.isExclusive());
			assertFalse(queue.isAutoDelete());
		});
		var retry = queues.stream().filter(queue -> queue.getName().equals("raw.retry.queue")).findFirst().orElseThrow();
		assertEquals(Map.of("x-queue-type", "quorum", "x-dead-letter-strategy", "at-least-once",
				"x-overflow", "reject-publish", "x-dead-letter-exchange", "raw.exchange",
				"x-dead-letter-routing-key", "raw.log"), retry.getArguments());
		var exchange = topology.getDeclarablesByType(DirectExchange.class).getFirst();
		assertEquals("dead-letter.exchange", exchange.getName());
		assertTrue(exchange.isDurable());
		var binding = topology.getDeclarablesByType(Binding.class).getFirst();
		assertEquals("dead-letter.exchange/dead-letter.log/dead-letter.queue",
				binding.getExchange() + "/" + binding.getRoutingKey() + "/" + binding.getDestination());
	}

	@Test
	void realPublishersWithholdNativeAckUntilPartialPublicationIsAccountedByConfirmedRetry() throws Exception {
		consumer.close();
		var rabbit = mock(RabbitTemplate.class);
		var publicationChannel = mock(PublisherCallbackChannel.class);
		doAnswer(call -> ((RabbitOperations.OperationsCallback<?>) call.getArgument(0)).doInRabbit(rabbit))
				.when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
		doAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(publicationChannel))
				.when(rabbit).execute(any(ChannelCallback.class));
		var retryConfirm = new CompletableFuture<org.springframework.amqp.rabbit.connection.CorrelationData>();
		var routes = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var retryBody = new CompletableFuture<byte[]>();
		doAnswer(call -> {
			String key = call.getArgument(1);
			Message message = call.getArgument(2);
			org.springframework.amqp.rabbit.connection.CorrelationData correlation = call.getArgument(3);
			routes.add(key);
			if (key.equals("raw.retry.queue")) {
				retryBody.complete(message.getBody());
				retryConfirm.complete(correlation);
			} else {
				correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(
						key.equals("processed.log"), null));
			}
			return null;
		}).when(rabbit).send(any(String.class), any(String.class), any(Message.class),
				any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
		var actualPublisher = new ProcessedLogPublisher(rabbit, mapper, Duration.ofSeconds(5));
		var actualFailures = new ProcessingFailurePublisher(rabbit, mapper, Duration.ofSeconds(5), 3,
				List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120)));
		consumer = new RawLogConsumer(normalizer, batches, actualPublisher, actualFailures, 16);
		Message raw = tagged(raw(2, "ERROR"), 9);
		Channel channel = mock(Channel.class);
		adapter().onMessage(raw, channel);
		persistence.getFirst().complete(null);
		var correlation = retryConfirm.get(2, TimeUnit.SECONDS);
		assertEquals(List.of("processed.log", "critical.log", "raw.retry.queue"), routes);
		org.junit.jupiter.api.Assertions.assertArrayEquals(raw.getBody(), retryBody.get(2, TimeUnit.SECONDS));
		verifyNoInteractions(channel);
		correlation.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true, null));
		verify(channel, org.mockito.Mockito.timeout(2000)).basicAck(9, false);
	}

	@Test
	void nativeAckWaitsForEveryPipelinedConfirmAndShutdownCannotBecomeLateSuccess() throws Exception {
		for (boolean shutdown : List.of(false, true)) {
			consumer.close();
			var rabbit = mock(RabbitTemplate.class);
			var publicationChannel = mock(PublisherCallbackChannel.class);
			doAnswer(call -> ((RabbitOperations.OperationsCallback<?>) call.getArgument(0)).doInRabbit(rabbit))
					.when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
			doAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(publicationChannel))
					.when(rabbit).execute(any(ChannelCallback.class));
			var sends = new LinkedBlockingQueue<CorrelationData>();
			doAnswer(call -> { sends.add(call.getArgument(3)); return null; })
					.when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
			var actualPublisher = new ProcessedLogPublisher(rabbit, mapper, Duration.ofSeconds(5));
			consumer = new RawLogConsumer(normalizer, batches, actualPublisher, failures, 16);
			var rawChannel = mock(Channel.class);
			adapter().onMessage(tagged(raw(2, "CRITICAL"), 67), rawChannel);
			persistence.getLast().complete(null);
			var confirmations = new ArrayList<CorrelationData>();
			for (int index = 0; index < 4; index++) {
				confirmations.add(sends.poll(2, TimeUnit.SECONDS));
				assertNotNull(confirmations.getLast());
			}
			verifyNoInteractions(rawChannel);
			for (int index = 3; index > 0; index--) {
				confirmations.get(index).getFuture().complete(new CorrelationData.Confirm(true, null));
			}
			verifyNoInteractions(rawChannel);
			if (shutdown) {
				consumer.close();
				verify(rawChannel, org.mockito.Mockito.timeout(2000)).basicReject(67, true);
			}
			confirmations.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null));
			if (shutdown) {
				verify(rawChannel, never()).basicAck(anyLong(), anyBoolean());
				verify(failures, never()).retry(any());
				verify(failures, never()).deadLetter(any(), any());
			} else {
				verify(rawChannel, org.mockito.Mockito.timeout(2000)).basicAck(67, false);
			}
		}
	}

	@Test
	void cachedClosedChannelCompletionCannotAckRecoveredDelivery() throws Exception {
		var client = mock(com.rabbitmq.client.ConnectionFactory.class);
		var connection = mock(com.rabbitmq.client.Connection.class);
		var physicalOld = mock(Channel.class);
		var physicalNew = mock(Channel.class);
		var open = new java.util.concurrent.atomic.AtomicBoolean(true);
		when(client.newConnection(org.mockito.ArgumentMatchers.nullable(java.util.concurrent.ExecutorService.class), any(String.class))).thenReturn(connection);
		when(connection.isOpen()).thenReturn(true);
		when(connection.createChannel()).thenReturn(physicalOld, physicalNew);
		when(physicalOld.isOpen()).thenAnswer(call -> open.get());
		when(physicalNew.isOpen()).thenReturn(true);
		var factory = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(client);
		try {
			var logicalConnection = factory.createConnection();
			Channel old = logicalConnection.createChannel(false);
			adapter().onMessage(tagged(raw(1, "INFO"), 1), old);
			open.set(false);
			Channel recovered = logicalConnection.createChannel(false);
			adapter().onMessage(tagged(raw(1, "INFO"), 1), recovered);
			persistence.getFirst().complete(null);
			work.runAll();
			verify(physicalOld, never()).basicAck(anyLong(), anyBoolean());
			verify(physicalNew, never()).basicAck(anyLong(), anyBoolean());
			persistence.getLast().complete(null);
			work.runAll();
			verify(physicalNew).basicAck(1, false);

		} finally {
			factory.destroy();
		}
	}

	@Test
	void invalidTransportContentTypeDoesNotBypassWholeBatchDlq() throws Exception {
		var props = new MessageProperties();
		props.setContentType("application/x-java-serialized-object");
		props.setDeliveryTag(19);
		var raw = new Message(new byte[] {1, 2, 3}, props);
		var channel = mock(Channel.class);
		adapter().onMessage(raw, channel);
		work.runAll();
		verify(failures).deadLetter(raw, ProcessingFailurePublisher.INVALID_BATCH);
		verifyNoInteractions(batches, publisher);
		verify(channel).basicAck(19, false);
	}

	@Test
	void rejectedCooldownSchedulingRequestsIndividualRequeueImmediately() throws Exception {
		Message raw = tagged(raw(1, "INFO"), 23);
		doThrow(new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE)).when(failures).retry(raw);
		when(cooldowns.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
				.thenThrow(new RejectedExecutionException());
		var channel = mock(Channel.class);
		adapter().onMessage(raw, channel);
		persistence.getFirst().completeExceptionally(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE));
		work.runAll();
		verify(channel).basicReject(23, true);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
	}

	private MessagingMessageListenerAdapter adapter() throws Exception {
		var method = RawLogConsumer.class.getMethod("consume", Message.class);
		var adapter = new MessagingMessageListenerAdapter(consumer, method);
		adapter.setHandlerAdapter(new HandlerAdapter(new InvocableHandlerMethod(consumer, method)));
		var factory = (SimpleRabbitListenerContainerFactory) ReflectionTestUtils.invokeMethod(
				new ProcessingConsumerConfiguration(), "processingRawListenerFactory", mock(ConnectionFactory.class), 16);
		adapter.setMessageConverter((org.springframework.amqp.support.converter.MessageConverter)
				ReflectionTestUtils.getField(factory, "messageConverter"));
		adapter.containerAckMode(AcknowledgeMode.MANUAL);
		adapter.setDefaultRequeueRejected(true);
		return adapter;
	}

	private void useRealBatchWriter(ClickHouseLogWriter inserted, AtomicLong nanos, List<Runnable> deadlines) {
		consumer.close();
		var timer = mock(ScheduledExecutorService.class);
		when(timer.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
			deadlines.add(call.getArgument(0));
			return mock(ScheduledFuture.class);
		});
		realBatches = batchWriter(inserted, timer, nanos::get);
		consumer = new RawLogConsumer(normalizer, realBatches, publisher, failures, 16, work.reopen(), cooldowns);
	}

	private void awaitQueuedAdmission(Semaphore semaphore) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!semaphore.hasQueuedThreads() && System.nanoTime() < deadline) Thread.onSpinWait();
		assertTrue(semaphore.hasQueuedThreads(), "Recovery/admission must be bounded at prefetch");
	}

	private AnnotationConfigApplicationContext context(Map<String, Object> settings) throws Exception {
		var context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", settings));
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
				.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
		context.registerBean("conversionService", ApplicationConversionService.class, () -> new ApplicationConversionService());
		context.registerBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));
		context.registerBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class));
		context.registerBean(ObjectMapper.class, () -> mapper);
		context.registerBean(ProcessingBatchWriter.class, () -> batches);
		context.registerBean(ProcessedLogPublisher.class, () -> publisher);
		context.registerBean("testDisableContainerStartup", BeanPostProcessor.class, () -> new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
				if (bean instanceof SimpleRabbitListenerContainerFactory factory) factory.setAutoStartup(false);
				return bean;
			}
		});
		context.register(ProcessingConsumerConfiguration.class);
		return context;
	}

	private Message raw(int count, String level) {
		var logs = IntStream.range(0, count).mapToObj(index -> new RawLogBatchEvent.Log(UUID.randomUUID(),
				"host", level, "message " + index, NOW.minusSeconds(2), "trace", Map.<String, Object>of("scope", "untrusted"))).toList();
		var event = new RawLogBatchEvent(UUID.randomUUID(), "raw.log", NOW.minusSeconds(1), "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(1), logs));
		var properties = new MessageProperties();
		properties.setMessageId(event.eventId().toString());
		properties.setContentType("application/json");
		return new Message(mapper.writeValueAsBytes(event), properties);
	}

	private Message tagged(Message message, long tag) {
		message.getMessageProperties().setDeliveryTag(tag);
		return message;
	}

	private void assertRequeue(CompletableFuture<Void> future) {
		var failure = assertThrows(CompletionException.class, future::join);
		assertInstanceOf(ImmediateRequeueAmqpException.class, failure.getCause());
		assertNull(failure.getCause().getCause());
	}

	private static class ManualExecutor extends AbstractExecutorService {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		private boolean stopped;
		void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
		ManualExecutor reopen() { stopped = false; return this; }
		@Override public void execute(Runnable task) {
			if (stopped) throw new RejectedExecutionException();
			tasks.addLast(task);
		}
		@Override public void shutdown() { stopped = true; }
		@Override public List<Runnable> shutdownNow() {
			stopped = true;
			var pending = List.copyOf(tasks);
			tasks.clear();
			return pending;
		}
		@Override public boolean isShutdown() { return stopped; }
		@Override public boolean isTerminated() { return stopped; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
	}

	private static RawLogNormalizer normalizer(ObjectMapper mapper, Clock clock) {
		try {
			return BeanUtils.instantiateClass(
					RawLogNormalizer.class.getDeclaredConstructor(ObjectMapper.class, Clock.class), mapper, clock);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(exception);
		}
	}

	private static ProcessingBatchWriter batchWriter(ClickHouseLogWriter writer,
			ScheduledExecutorService scheduler, LongSupplier nanoTime) {
		try {
			return BeanUtils.instantiateClass(ProcessingBatchWriter.class
					.getDeclaredConstructor(ClickHouseLogWriter.class, ScheduledExecutorService.class,
							LongSupplier.class), writer, scheduler, nanoTime);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(exception);
		}
	}
}