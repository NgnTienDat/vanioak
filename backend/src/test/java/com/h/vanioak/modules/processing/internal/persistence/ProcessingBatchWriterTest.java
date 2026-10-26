package com.h.vanioak.modules.processing.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.config.ProcessingConfiguration;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ProcessingBatchWriterTest {

	private final ClickHouseLogWriter writer = mock(ClickHouseLogWriter.class);
	private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
	private final AtomicLong nanos = new AtomicLong();
	private final List<Deadline> deadlines = Collections.synchronizedList(new ArrayList<>());
	private final List<List<NormalizedLog>> writes = Collections.synchronizedList(new ArrayList<>());
	private ProcessingBatchWriter batchWriter;

	@BeforeEach
	void setUp() {
		when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
			ScheduledFuture<?> future = mock(ScheduledFuture.class);
			deadlines.add(new Deadline(call.getArgument(0), call.getArgument(1), future));
			return future;
		});
		doAnswer(call -> {
			writes.add(List.copyOf(call.getArgument(0)));
			return null;
		}).when(writer).write(anyList());
		batchWriter = new ProcessingBatchWriter(writer, scheduler, nanos::get);
	}

	@AfterEach
	void close() {
		batchWriter.close();
	}

	@Test
	void flushesExactlyAtAThousandAndCompletesBothSubmissions() {
		var first = logs(999);
		var firstTicket = batchWriter.submit(first).toCompletableFuture();
		assertFalse(firstTicket.isDone());
		assertTrue(writes.isEmpty());
		var last = logs(1);
		var secondTicket = batchWriter.submit(last).toCompletableFuture();
		assertTrue(firstTicket.isDone());
		assertTrue(secondTicket.isDone());
		assertDoesNotThrow(firstTicket::join);
		assertEquals(1, writes.size());
		assertEquals(1000, writes.getFirst().size());
		assertEquals(first, writes.getFirst().subList(0, 999));
		assertEquals(last.getFirst(), writes.getFirst().getLast());
		verify(deadlines.getFirst().future()).cancel(false);
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		deadlines.getFirst().task().run();
		assertEquals(1, writes.size());
	}

	@Test
	void splitsLargeSubmissionsAndWaitsForTheirFinalChunk() {
		var input = logs(2501);
		var ticket = batchWriter.submit(input).toCompletableFuture();
		assertEquals(List.of(1000, 1000), writes.stream().map(List::size).toList());
		assertFalse(ticket.isDone());
		assertEquals(TimeUnit.SECONDS.toNanos(2), latest().delay());
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertDoesNotThrow(ticket::join);
		assertEquals(List.of(1000, 1000, 501), writes.stream().map(List::size).toList());
		assertEquals(input, writes.stream().flatMap(List::stream).toList());
	}

	@Test
	void usesOldestDeadlineAndStaleTasksCannotFlushANewerBufferEarly() {
		var first = batchWriter.submit(logs(1)).toCompletableFuture();
		Deadline old = latest();
		nanos.set(TimeUnit.MILLISECONDS.toNanos(1500));
		var second = batchWriter.submit(logs(1)).toCompletableFuture();
		assertEquals(TimeUnit.MILLISECONDS.toNanos(500), latest().delay());
		old.task().run();
		assertTrue(writes.isEmpty());
		assertFalse(first.isDone());
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertDoesNotThrow(first::join);
		assertDoesNotThrow(second::join);
		assertEquals(2, writes.getFirst().size());
		var newer = batchWriter.submit(logs(1)).toCompletableFuture();
		old.task().run();
		assertFalse(newer.isDone());
		assertEquals(1, writes.size());
		assertEquals(TimeUnit.SECONDS.toNanos(2), latest().delay());
		nanos.set(TimeUnit.SECONDS.toNanos(4));
		latest().task().run();
		assertDoesNotThrow(newer::join);
	}

	@Test
	void keepsOriginalDeadlineForRemainderAfterThresholdFlush() {
		batchWriter.submit(logs(900));
		nanos.set(TimeUnit.MILLISECONDS.toNanos(1500));
		var crossing = batchWriter.submit(logs(200)).toCompletableFuture();
		assertEquals(1, writes.size());
		assertFalse(crossing.isDone());
		assertEquals(TimeUnit.SECONDS.toNanos(2), latest().delay());
		nanos.set(TimeUnit.SECONDS.toNanos(3));
		latest().task().run();
		assertEquals(1, writes.size());
		nanos.set(TimeUnit.MILLISECONDS.toNanos(3500));
		latest().task().run();
		assertEquals(100, writes.getLast().size());
		assertDoesNotThrow(crossing::join);
	}

	@Test
	void failsAllAffectedSubmissionsAndDropsUnwrittenTailsWithoutRetrying() {
		doThrow(new IllegalStateException("sensitive body")).when(writer).write(anyList());
		var first = batchWriter.submit(logs(900)).toCompletableFuture();
		var second = batchWriter.submit(logs(200)).toCompletableFuture();
		assertFailed(first);
		assertFailed(second);
		verify(writer).write(anyList());
		nanos.set(TimeUnit.SECONDS.toNanos(10));
		deadlines.getFirst().task().run();
		verify(writer).write(anyList());
		doNothing().when(writer).write(anyList());
		var later = batchWriter.submit(logs(1000)).toCompletableFuture();
		assertDoesNotThrow(later::join);
		verify(writer, times(2)).write(anyList());
		assertFailed(first);
		assertFailed(second);
	}

	@Test
	void preservesEarlierSuccessAndCallerRetryIdsAfterALaterChunkFails() {
		var first = batchWriter.submit(logs(500)).toCompletableFuture();
		var input = logs(2500);
		AtomicInteger calls = new AtomicInteger();
		doAnswer(call -> {
			writes.add(List.copyOf(call.getArgument(0)));
			if (calls.incrementAndGet() == 2) {
				throw new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE);
			}
			return null;
		}).when(writer).write(anyList());
		var failed = batchWriter.submit(input).toCompletableFuture();
		assertDoesNotThrow(first::join);
		assertFailed(failed);
		assertEquals(2, writes.size());
		var retried = batchWriter.submit(input).toCompletableFuture();
		assertFalse(retried.isDone());
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertDoesNotThrow(retried::join);
		assertEquals(input, writes.subList(2, writes.size()).stream().flatMap(List::stream).toList());
		assertFailed(failed);
	}

	@Test
	void timerFailureIsExceptionalAndDoesNotLeaveFailedRecordsForShutdown() {
		var ticket = batchWriter.submit(logs(1)).toCompletableFuture();
		doThrow(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE)).when(writer).write(anyList());
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertFailed(ticket);
		batchWriter.close();
		verify(writer).write(anyList());
	}

	@Test
	void schedulerRejectionFailsAcceptedWorkRatherThanLeavingPendingTickets() {
		when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
				.thenThrow(new RejectedExecutionException("sensitive scheduler message"));
		var ticket = batchWriter.submit(logs(1)).toCompletableFuture();
		assertFailed(ticket);
		batchWriter.close();
		verifyNoInteractions(writer);
	}

	@Test
	void copiesSubmissionsAndKeepsDuplicateIdsAsIndependentAttempts() {
		var input = new ArrayList<>(logs(1));
		var first = batchWriter.submit(input).toCompletableFuture();
		var second = batchWriter.submit(input).toCompletableFuture();
		input.clear();
		assertNotSame(first, second);
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertDoesNotThrow(first::join);
		assertDoesNotThrow(second::join);
		assertEquals(2, writes.getFirst().size());
		assertEquals(writes.getFirst().getFirst(), writes.getFirst().getLast());
	}

	@Test
	void rejectsNullAndEmptyInputBeforeAcceptingAnything() {
		assertThrows(NullPointerException.class, () -> batchWriter.submit(null));
		assertThrows(NullPointerException.class, () -> batchWriter.submit(java.util.Arrays.asList(logs(1).getFirst(), null)));
		assertThrows(IllegalArgumentException.class, () -> batchWriter.submit(List.of()));
		assertTrue(deadlines.isEmpty());
		verifyNoInteractions(writer);
	}

	@Test
	void closesWithConfirmedPersistenceOrExceptionalTicketsAndStopsAdmission() {
		var success = batchWriter.submit(logs(1)).toCompletableFuture();
		batchWriter.close();
		assertDoesNotThrow(success::join);
		verify(scheduler).shutdown();
		verify(latest().future()).cancel(false);
		assertThrows(IllegalStateException.class, () -> batchWriter.submit(logs(1)));
		batchWriter.close();
		verify(writer).write(anyList());
	}

	@Test
	void failedShutdownReportsFailureAndNeverClaimsPersistence() {
		var ticket = batchWriter.submit(logs(1)).toCompletableFuture();
		doThrow(new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE)).when(writer).write(anyList());
		batchWriter.close();
		assertFailed(ticket);
		verify(scheduler).shutdown();
		verify(writer).write(anyList());
	}

	@Test
	void emptyShutdownDoesNotInsertOrSchedule() {
		batchWriter.close();
		verifyNoInteractions(writer);
		assertTrue(deadlines.isEmpty());
		verify(scheduler).shutdown();
	}

	@Test
	void concurrentSubmissionAndTimerExecutionSerializeInsertsWithoutLosingMembership() throws Exception {
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		doAnswer(call -> {
			maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
			writes.add(List.copyOf(call.getArgument(0)));
			Thread.yield();
			active.decrementAndGet();
			return null;
		}).when(writer).write(anyList());
		var input = logs(3201);
		var initial = batchWriter.submit(input.subList(0, 1)).toCompletableFuture();
		Deadline deadline = latest();
		var tickets = Collections.synchronizedList(new ArrayList<CompletableFuture<Void>>());
		tickets.add(initial);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<java.util.concurrent.Future<?>>();
			for (int index = 0; index < 32; index++) {
				int offset = index * 100 + 1;
				tasks.add(executor.submit(() -> {
					assertTrue(start.await(5, TimeUnit.SECONDS));
					tickets.add(batchWriter.submit(input.subList(offset, offset + 100)).toCompletableFuture());
					return null;
				}));
			}
			tasks.add(executor.submit(() -> {
				nanos.set(TimeUnit.SECONDS.toNanos(2));
				deadline.task().run();
			}));
			start.countDown();
			for (var task : tasks) {
				task.get(5, TimeUnit.SECONDS);
			}
		}
		batchWriter.close();
		tickets.forEach(ticket -> assertDoesNotThrow(ticket::join));
		var stored = writes.stream().flatMap(List::stream).toList();
		assertEquals(input.size(), stored.size());
		assertEquals(new HashSet<>(input), new HashSet<>(stored));
		assertTrue(writes.stream().allMatch(batch -> batch.size() <= 1000));
		assertEquals(1, maximum.get());
	}

	@Test
	void completionCallbacksCanSubmitFromAnotherThreadOutsideTheMonitor() {
		var first = batchWriter.submit(logs(1));
		var callback = first.thenRun(() -> {
			var executor = Executors.newSingleThreadExecutor();
			try {
				executor.submit(() -> batchWriter.submit(logs(1))).get(5, TimeUnit.SECONDS);
			} catch (Exception exception) {
				throw new AssertionError("Completion held the batch monitor", exception);
			} finally {
				executor.shutdownNow();
			}
		});
		nanos.set(TimeUnit.SECONDS.toNanos(2));
		latest().task().run();
		assertDoesNotThrow(() -> callback.toCompletableFuture().join());
		assertEquals(1, writes.size());
		batchWriter.close();
		assertEquals(2, writes.size());
	}

	@Test
	void bindsActualYamlDefaultsWithoutStartupIoAndClosesThroughSpring() throws Exception {
		var transport = mock(ClickHouseConnection.class);
		ProcessingBatchWriter bean;
		try (var context = new AnnotationConfigApplicationContext()) {
			var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
			sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
			context.registerBean(ClickHouseConnection.class, () -> transport);
			context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
			context.register(ProcessingConfiguration.class);
			context.refresh();
			assertEquals("1000", context.getEnvironment().getProperty("vanioak.processing.batch-size"));
			assertEquals("2000", context.getEnvironment().getProperty("vanioak.processing.batch-timeout-ms"));
			bean = context.getBean(ProcessingBatchWriter.class);
			verifyNoInteractions(transport);
		}
		ProcessingBatchWriter closed = bean;
		assertThrows(IllegalStateException.class, () -> closed.submit(logs(1)));
	}

	@Test
	void rejectsConfigurationThatChangesTheMvpPolicy() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> new ProcessingBatchWriter(writer, 999, 2000));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingBatchWriter(writer, 1000, 1999));
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
					"PROCESSING_BATCH_SIZE", "1000", "PROCESSING_BATCH_TIMEOUT_MS", "1000")));
			new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
					.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
			context.registerBean(ClickHouseConnection.class, () -> mock(ClickHouseConnection.class));
			context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
			context.register(ProcessingConfiguration.class);
			assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
		}
	}

	private Deadline latest() {
		return deadlines.getLast();
	}

	private void assertFailed(CompletableFuture<Void> ticket) {
		assertTrue(ticket.isCompletedExceptionally());
		var failure = assertThrows(CompletionException.class, ticket::join).getCause();
		assertInstanceOf(ProcessingException.class, failure);
		assertEquals(ErrorCode.PERSISTENCE_UNAVAILABLE, ((ProcessingException) failure).getErrorCode());
		assertEquals("Log persistence unavailable", failure.getMessage());
		assertNull(failure.getCause());
	}

	private List<NormalizedLog> logs(int count) {
		Instant time = Instant.parse("2026-10-10T00:00:00Z");
		UUID application = UUID.randomUUID();
		UUID environment = UUID.randomUUID();
		return IntStream.range(0, count).mapToObj(index -> new NormalizedLog(UUID.randomUUID(), application,
				environment, "127.0.0.1", "INFO", "message", time, "trace", "{}", time, time)).toList();
	}

	private record Deadline(Runnable task, long delay, ScheduledFuture<?> future) { }
}
