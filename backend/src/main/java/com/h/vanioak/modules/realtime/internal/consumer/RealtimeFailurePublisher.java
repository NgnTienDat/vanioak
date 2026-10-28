package com.h.vanioak.modules.realtime.internal.consumer;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.Channel;

public final class RealtimeFailurePublisher implements AutoCloseable {
	private final RabbitTemplate rabbit;
	private final Clock clock;
	private final Duration timeout;
	private final Sleeper sleeper;
	private final AtomicBoolean occupied = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(1), task -> { var thread = new Thread(task, "realtime-dlq"); thread.setDaemon(true); return thread; });

	public RealtimeFailurePublisher(RabbitTemplate dedicatedTemplate) {
		this(dedicatedTemplate, Clock.systemUTC(), Duration.ofSeconds(5), Thread::sleep);
	}

	RealtimeFailurePublisher(RabbitTemplate dedicatedTemplate, Clock clock, Duration timeout, Sleeper sleeper) {
		this.rabbit = dedicatedTemplate;
		this.rabbit.setMandatory(true);
		// CorrelationData retains returned-message status; the callback must not log source bodies.
		this.rabbit.setReturnsCallback(returned -> { });
		this.clock = clock;
		this.timeout = timeout;
		this.sleeper = sleeper;
	}

	public CompletionStage<Boolean> publish(RealtimeEventDecoder.Decoded decoded, String messageId) {
		if (decoded.kind() != RealtimeEventDecoder.Kind.INVALID && decoded.kind() != RealtimeEventDecoder.Kind.UNSUPPORTED)
			return CompletableFuture.failedFuture(new RealtimeJson.CapacityException());
		if (stopped.get() || !occupied.compareAndSet(false, true))
			return CompletableFuture.failedFuture(new RealtimeJson.CapacityException());
		var result = new CompletableFuture<Boolean>();
		try {
			Message message = message(decoded, messageId);
			worker.execute(() -> {
				boolean settled = true;
				boolean success = false;
				try {
					for (int attempt = 0; attempt < 3 && !stopped.get(); attempt++) {
						Attempt outcome = attempt(message);
						settled = outcome.settled();
						if (outcome.success()) { success = true; break; }
						if (!settled) break;
						if (attempt < 2 && !stopped.get()) sleeper.sleep(5000);
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} catch (RuntimeException ex) {
					settled = false;
				} finally {
					if (settled) occupied.set(false);
					else stopped.set(true); // Keep the sole slot held: native completion was not established.
				}
				result.complete(success);
			});
		} catch (RuntimeException ex) {
			occupied.set(false);
			result.completeExceptionally(ex);
		}
		// Cancellation of this view cannot cancel the native correlation or reclaim its slot.
		return result.minimalCompletionStage();
	}

	private Message message(RealtimeEventDecoder.Decoded decoded, String messageId) {
		var mapper = RealtimeJson.reader();
		UUID id = decoded.original() != null && decoded.original().get("event_id") != null
				&& decoded.original().get("event_id").isString()
				? RealtimeEventDecoder.canonical(decoded.original().get("event_id").asString()) : null;
		if (id == null) id = RealtimeEventDecoder.canonical(messageId);
		if (id == null) id = UUID.randomUUID();
		String failed = clock.instant().toString();
		var root = mapper.createObjectNode();
		root.put("event_id", id.toString()).put("event_type", "dead-letter.log").put("occurred_at", failed)
				.put("producer", "realtime").put("schema_version", 1);
		var payload = root.putObject("payload");
		payload.put("event_id", id.toString()).put("reason", decoded.reason()).put("field", "$")
				.put("failed_at", failed);
		payload.set("original_message", decoded.original() != null ? decoded.original()
				: mapper.createObjectNode().put("body_base64", Base64.getEncoder().encodeToString(decoded.raw())));
		byte[] body = RealtimeJson.encode(mapper, root, RealtimeJson.DLQ_LIMIT);
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(id.toString());
		return new Message(body, properties);
	}

	private Attempt attempt(Message message) {
		long deadline = System.nanoTime() + timeout.toNanos();
		var correlation = new CorrelationData(UUID.randomUUID().toString());
		Channel[] owned = {null};
		try {
			boolean success = Boolean.TRUE.equals(rabbit.invoke(operations -> rabbit.execute(channel -> {
				owned[0] = channel instanceof ChannelProxy proxy ? proxy.getTargetChannel() : channel;
				try {
					operations.send("dead-letter.exchange", "dead-letter.log", message, correlation);
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
					var confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
					return confirm != null && confirm.ack() && correlation.getReturned() == null;
				} catch (Exception ex) {
					if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
					RabbitUtils.setPhysicalCloseRequired(channel, true);
					return false;
				}
			})));
			if (success) return new Attempt(true, true);
		} catch (RuntimeException ex) { /* Native ownership is checked below, never inferred from a send exception. */ }
		boolean nativeTerminal = correlation.getFuture().isDone() && !correlation.getFuture().isCancelled()
				&& !correlation.getFuture().isCompletedExceptionally() && correlation.getFuture().getNow(null) != null;
		boolean retired = owned[0] instanceof PublisherCallbackChannel callback && !owned[0].isOpen()
				&& callback.getPendingConfirmsCount() == 0;
		return new Attempt(false, nativeTerminal || retired);
	}

	boolean occupied() { return occupied.get(); }
	@Override public void close() { stopped.set(true); worker.shutdown(); }
	interface Sleeper { void sleep(long millis) throws InterruptedException; }
	private record Attempt(boolean success, boolean settled) { }
}
