package com.h.vanioak.modules.processing.internal.persistence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;

public class ProcessingBatchWriter implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(ProcessingBatchWriter.class);
	private static final int BATCH_SIZE = 1000;
	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
	private final Object monitor = new Object();
	private final ClickHouseLogWriter writer;
	private final ScheduledExecutorService scheduler;
	private final LongSupplier nanoTime;
	private final ArrayDeque<Submission> submissions = new ArrayDeque<>();
	private long buffered;
	private ScheduledFuture<?> timer;
	private boolean closed;

	public ProcessingBatchWriter(ClickHouseLogWriter writer, int batchSize, long timeoutMillis) {
		if (batchSize != BATCH_SIZE || timeoutMillis != 2000) {
			throw new IllegalArgumentException("Processing requires a 1000-log / 2000-ms flush policy");
		}
		this.writer = writer;
		var executor = new ScheduledThreadPoolExecutor(1);
		executor.setRemoveOnCancelPolicy(true);
		this.scheduler = executor;
		this.nanoTime = System::nanoTime;
	}

	ProcessingBatchWriter(ClickHouseLogWriter writer, ScheduledExecutorService scheduler, LongSupplier nanoTime) {
		this.writer = writer;
		this.scheduler = scheduler;
		this.nanoTime = nanoTime;
	}

	public CompletionStage<Void> submit(List<NormalizedLog> logs) {
		List<NormalizedLog> records = List.copyOf(logs);
		if (records.isEmpty()) {
			throw new IllegalArgumentException("A submission requires normalized logs");
		}
		var completions = new ArrayList<Runnable>();
		Submission submission;
		synchronized (monitor) {
			if (closed) {
				throw new IllegalStateException("Processing batch writer is closed");
			}
			submission = new Submission(records, nanoTime.getAsLong());
			submissions.addLast(submission);
			buffered += records.size();
			cancelTimer();
			while (buffered >= BATCH_SIZE) {
				writeBatch(completions);
			}
			scheduleDeadline(completions);
		}
		completions.forEach(Runnable::run);
		return submission.completion.minimalCompletionStage();
	}

	private void writeBatch(List<Runnable> completions) {
		var batch = new ArrayList<NormalizedLog>(BATCH_SIZE);
		int affected = 0;
		for (Submission submission : submissions) {
			int end = Math.min(submission.logs.size(), submission.offset + BATCH_SIZE - batch.size());
			batch.addAll(submission.logs.subList(submission.offset, end));
			affected++;
			if (batch.size() == BATCH_SIZE) {
				break;
			}
		}
		try {
			// shortcut: serialized HTTP blocks admission; split I/O from state if measured throughput requires it.
			writer.write(List.copyOf(batch));
		} catch (RuntimeException exception) {
			LOG.warn("Processing insert unconfirmed; failing {} submissions (type {})", affected,
					exception.getClass().getSimpleName());
			for (int index = 0; index < affected; index++) {
				failFirst(completions);
			}
			return;
		}
		int remaining = batch.size();
		buffered -= remaining;
		while (remaining > 0) {
			Submission first = submissions.getFirst();
			int persisted = Math.min(remaining, first.logs.size() - first.offset);
			first.offset += persisted;
			remaining -= persisted;
			if (first.offset == first.logs.size()) {
				submissions.removeFirst();
				completions.add(() -> first.completion.complete(null));
			}
		}
	}

	private void failFirst(List<Runnable> completions) {
		Submission failed = submissions.removeFirst();
		buffered -= failed.logs.size() - failed.offset;
		completions.add(() -> failed.completion.completeExceptionally(
				new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE)));
	}

	private void scheduleDeadline(List<Runnable> completions) {
		if (submissions.isEmpty()) {
			return;
		}
		long delay = Math.max(0, TIMEOUT_NANOS - (nanoTime.getAsLong() - submissions.getFirst().enqueuedAt));
		try {
			timer = scheduler.schedule(this::onDeadline, delay, TimeUnit.NANOSECONDS);
		} catch (RejectedExecutionException exception) {
			LOG.warn("Processing deadline unavailable; failing pending submissions");
			while (!submissions.isEmpty()) {
				failFirst(completions);
			}
		}
	}

	private void onDeadline() {
		var completions = new ArrayList<Runnable>();
		synchronized (monitor) {
			if (closed || submissions.isEmpty()) {
				return;
			}
			cancelTimer();
			while (!submissions.isEmpty()
					&& nanoTime.getAsLong() - submissions.getFirst().enqueuedAt >= TIMEOUT_NANOS) {
				writeBatch(completions);
			}
			scheduleDeadline(completions);
		}
		completions.forEach(Runnable::run);
	}

	private void cancelTimer() {
		if (timer != null) {
			timer.cancel(false);
			timer = null;
		}
	}

	@Override
	public void close() {
		var completions = new ArrayList<Runnable>();
		synchronized (monitor) {
			if (closed) {
				return;
			}
			closed = true;
			cancelTimer();
			while (!submissions.isEmpty()) {
				writeBatch(completions);
			}
		}
		scheduler.shutdown();
		completions.forEach(Runnable::run);
	}

	private static final class Submission {
		private final List<NormalizedLog> logs;
		private final long enqueuedAt;
		private final CompletableFuture<Void> completion = new CompletableFuture<>();
		private int offset;

		private Submission(List<NormalizedLog> logs, long enqueuedAt) {
			this.logs = logs;
			this.enqueuedAt = enqueuedAt;
		}
	}
}
