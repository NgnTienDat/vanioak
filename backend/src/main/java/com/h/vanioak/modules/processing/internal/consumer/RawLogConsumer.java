package com.h.vanioak.modules.processing.internal.consumer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;
import com.h.vanioak.modules.processing.internal.publisher.ProcessingFailurePublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RawLogConsumer implements AutoCloseable {

	private final Object lifecycle = new Object();
	private final Set<CompletableFuture<Void>> active = new HashSet<>();
	private final RawLogNormalizer normalizer;
	private final ProcessingBatchWriter batchWriter;
	private final ProcessedLogPublisher publisher;
	private final ProcessingFailurePublisher failures;
	private final ExecutorService publications;
	private final ScheduledExecutorService cooldowns;
	private final Semaphore admission;
	private volatile boolean closed;

	public RawLogConsumer(RawLogNormalizer normalizer, ProcessingBatchWriter batchWriter,
			ProcessedLogPublisher publisher, ProcessingFailurePublisher failures, int prefetch) {
		this(normalizer, batchWriter, publisher, failures, prefetch,
				new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(prefetch)),
				new ScheduledThreadPoolExecutor(1));
	}

	RawLogConsumer(RawLogNormalizer normalizer, ProcessingBatchWriter batchWriter,
			ProcessedLogPublisher publisher, ProcessingFailurePublisher failures, int prefetch,
			ExecutorService publications, ScheduledExecutorService cooldowns) {
		if (prefetch < 1) {
			throw new IllegalArgumentException("Processing prefetch must be positive");
		}
		this.normalizer = normalizer;
		this.batchWriter = batchWriter;
		this.publisher = publisher;
		this.failures = failures;
		this.publications = publications;
		this.cooldowns = cooldowns;
		this.admission = new Semaphore(prefetch);
	}

	@RabbitListener(id = "processingRawConsumer", queues = "raw.queue",
			containerFactory = "processingRawListenerFactory")
	public CompletableFuture<Void> consume(Message raw) {
		var completion = new CompletableFuture<Void>();
		try {
			admission.acquire();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			requeue(completion);
			return completion;
		}
		synchronized (lifecycle) {
			if (closed) {
				admission.release();
				requeue(completion);
				return completion;
			}
			active.add(completion);
		}
		completion.whenComplete((unused, failure) -> {
			synchronized (lifecycle) {
				active.remove(completion);
			}
			admission.release();
		});
		try {
			if (failures.retryCount(raw) < 0) {
				dispatch(completion, () -> transfer(raw, completion, ProcessingFailurePublisher.INVALID_RETRY));
				return completion;
			}
			List<NormalizedLog> logs = normalizer.normalize(raw.getBody());
			batchWriter.submit(logs).whenComplete((unused, failure) ->
					dispatch(completion, () -> afterPersistence(raw, logs, failure, completion)));
		} catch (RuntimeException exception) {
			log.warn("Raw processing failed before persistence: failure={}", exception.getClass().getSimpleName());
			String reason = exception instanceof ProcessingException processing
					&& processing.getErrorCode() == ErrorCode.INVALID_RAW_BATCH
							? ProcessingFailurePublisher.INVALID_BATCH : null;
			dispatch(completion, () -> transfer(raw, completion, reason));
		}
		return completion;
	}

	private void afterPersistence(Message raw, List<NormalizedLog> logs, Throwable failure,
			CompletableFuture<Void> completion) {
		if (failure != null) {
			log.warn("Raw persistence unconfirmed: failure={}", failure.getClass().getSimpleName());
			transfer(raw, completion, null);
			return;
		}
		try {
			if (closed || completion.isDone()) {
				requeue(completion);
				return;
			}
			publisher.publishBatch(logs);
			if (closed || completion.isDone()) {
				requeue(completion);
				return;
			}
			completion.complete(null);
		} catch (RuntimeException exception) {
			log.warn("Raw downstream publication unconfirmed: failure={}", exception.getClass().getSimpleName());
			transfer(raw, completion, null);
		}
	}

	private void transfer(Message raw, CompletableFuture<Void> completion, String permanentReason) {
		if (closed || completion.isDone() || Thread.currentThread().isInterrupted()) {
			requeue(completion);
			return;
		}
		try {
			if (permanentReason == null) {
				failures.retry(raw);
			} else {
				failures.deadLetter(raw, permanentReason);
			}
			completion.complete(null);
		} catch (RuntimeException exception) {
			log.warn("Raw failure handoff unconfirmed; requeue after cooldown: failure={}",
					exception.getClass().getSimpleName());
			try {
				cooldowns.schedule(() -> requeue(completion), failures.cooldown().toMillis(), TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException rejected) {
				requeue(completion);
			}
		}
	}

	private void dispatch(CompletableFuture<Void> completion, Runnable work) {
		if (closed || completion.isDone()) {
			requeue(completion);
			return;
		}
		try {
			publications.execute(() -> {
				if (closed || completion.isDone()) {
					requeue(completion);
					return;
				}
				try {
					work.run();
				} catch (RuntimeException exception) {
					log.warn("Raw continuation failed; requesting requeue: failure={}",
							exception.getClass().getSimpleName());
					requeue(completion);
				}
			});
		} catch (RejectedExecutionException exception) {
			requeue(completion);
		}
	}

	private void requeue(CompletableFuture<Void> completion) {
		completion.completeExceptionally(new ImmediateRequeueAmqpException("Raw delivery remains unaccounted"));
	}

	@Override
	public void close() {
		List<CompletableFuture<Void>> pending;
		synchronized (lifecycle) {
			if (closed) {
				return;
			}
			closed = true;
			pending = List.copyOf(active);
		}
		pending.forEach(this::requeue);
		publications.shutdownNow();
		cooldowns.shutdownNow();
	}
}