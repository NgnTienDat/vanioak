package com.h.vanioak.modules.processing.internal.publisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
public class ProcessedLogPublisher {

	public static final String PROCESSED_EXCHANGE = "processed.exchange";
	public static final String PROCESSED_QUEUE = "realtime.queue";
	public static final String PROCESSED_ROUTING_KEY = "processed.log";
	
	public static final String CRITICAL_EXCHANGE = "critical.exchange";
	public static final String CRITICAL_QUEUE = "alert.queue";
	public static final String CRITICAL_ROUTING_KEY = "critical.log";
	private static final int PUBLICATION_WINDOW = 64;
	private final Semaphore slots = new Semaphore(PUBLICATION_WINDOW, true);
	private final List<UncertainSend> uncertainSends = new ArrayList<>();
	private final RabbitTemplate rabbit;
	private final ObjectMapper mapper;
	private final ObjectReader metadataReader;
	private final Duration confirmTimeout;

	public ProcessedLogPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
			@Value("${vanioak.processing.publish-confirm-timeout}") Duration confirmTimeout) {
		if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.toMillis() < 1) {
			throw new IllegalArgumentException("Publish confirmation timeout must be positive");
		}
		this.rabbit = rabbit;
		this.mapper = mapper;
		this.metadataReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
		this.confirmTimeout = confirmTimeout;
	}

	/** The caller must confirm persistence before invoking this method. */
	public void publish(NormalizedLog log) {
		publishBatch(List.of(log));
	}

	/** Required processed and critical publications share a bounded transport window. */
	public void publishBatch(List<NormalizedLog> records) {
		Objects.requireNonNull(records);
		var window = new ArrayList<Message>(PUBLICATION_WINDOW);
		for (NormalizedLog record : records) {
			Objects.requireNonNull(record);
			var processed = payload(record);
			processed.put("host_ip", record.hostIp());
			processed.set("metadata", metadata(record.metadataJson()));
			processed.put("processed_at", record.processedAt().toString());
			byte[] processedBody = envelope(record, PROCESSED_ROUTING_KEY, processed);
			byte[] criticalBody = "ERROR".equals(record.level()) || "CRITICAL".equals(record.level())
					? envelope(record, CRITICAL_ROUTING_KEY, payload(record)) : null;
			append(window, record, PROCESSED_ROUTING_KEY, processedBody);
			if (criticalBody != null) {
				append(window, record, CRITICAL_ROUTING_KEY, criticalBody);
			}
		}
		if (!window.isEmpty()) {
			publishWindow(window);
		}
	}

	private void append(List<Message> window, NormalizedLog record, String routingKey, byte[] body) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(record.eventId().toString());
		properties.setReceivedRoutingKey(routingKey);
		window.add(new Message(body, properties));
		if (window.size() == PUBLICATION_WINDOW) {
			publishWindow(window);
			window.clear();
		}
	}

	private void publishWindow(List<Message> messages) {
		long deadline = System.nanoTime() + confirmTimeout.toNanos();
		try {
			// A failed send may throw before Spring registers its correlation. Reclaim only after native cleanup.
			synchronized (uncertainSends) {
				uncertainSends.removeIf(send -> {
					if (send.correlation().getFuture().isDone()) {
						return true;
					}
					if (send.channel().getPendingConfirmsCount() == 0) {
						send.correlation().getFuture().completeExceptionally(new IllegalStateException("Publication channel retired"));
						return true;
					}
					return false;
				});
			}
			if (!slots.tryAcquire(messages.size(), remaining(deadline), TimeUnit.NANOSECONDS)) {
				throw new TimeoutException();
			}
			int[] unused = {messages.size()};
			try {
				rabbit.invoke(operations -> rabbit.execute(channel -> {
					var nativeChannel = (PublisherCallbackChannel) (channel instanceof ChannelProxy proxy
							? proxy.getTargetChannel() : channel);
					var confirmations = new ArrayList<CorrelationData>(messages.size());
					try {
						for (Message message : messages) {
							if (Thread.currentThread().isInterrupted()) {
								throw new InterruptedException();
							}
							remaining(deadline);
							validateCompleted(confirmations);
							var correlation = new CorrelationData();
							correlation.getFuture().whenComplete((confirm, failure) -> slots.release());
							unused[0]--;
							confirmations.add(correlation);
							String route = message.getMessageProperties().getReceivedRoutingKey();
							try {
								operations.send(PROCESSED_ROUTING_KEY.equals(route) ? PROCESSED_EXCHANGE : CRITICAL_EXCHANGE,
										route, message, correlation);
							} catch (RuntimeException exception) {
								synchronized (uncertainSends) {
									uncertainSends.removeIf(send -> send.correlation().getFuture().isDone());
									if (!correlation.getFuture().isDone()) {
										uncertainSends.add(new UncertainSend(correlation, nativeChannel));
									}
								}
								throw exception;
							}
						}
						CompletableFuture.allOf(confirmations.stream().map(CorrelationData::getFuture)
								.toArray(CompletableFuture[]::new)).get(remaining(deadline), TimeUnit.NANOSECONDS);
						validateCompleted(confirmations);
						return null;
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						RabbitUtils.setPhysicalCloseRequired(channel, true);
						throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
					} catch (RuntimeException | TimeoutException | ExecutionException exception) {
						// invoke owns this channel; physical retirement triggers native NACKs for unresolved confirms.
						RabbitUtils.setPhysicalCloseRequired(channel, true);
						log.warn("Log publication window failed: failure={}", exception.getClass().getSimpleName());
						throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
					}
				}));
			} finally {
				slots.release(unused[0]);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
		} catch (AmqpException | TimeoutException | CancellationException | CompletionException exception) {
			log.warn("Log publication window unavailable: failure={}", exception.getClass().getSimpleName());
			throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
		}
	}

	private long remaining(long deadline) throws TimeoutException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw new TimeoutException();
		}
		return remaining;
	}

	private void validateCompleted(List<CorrelationData> confirmations) {
		for (CorrelationData correlation : confirmations) {
			if (correlation.getFuture().isDone()
					&& (!correlation.getFuture().join().ack() || correlation.getReturned() != null)) {
				throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
			}
		}
	}

	private ObjectNode payload(NormalizedLog log) {
		var payload = mapper.createObjectNode();
		payload.put("event_id", log.eventId().toString());
		payload.put("application_id", log.applicationId().toString());
		payload.put("environment_id", log.environmentId().toString());
		payload.put("level", log.level());
		payload.put("message", log.message());
		payload.put("timestamp", log.timestamp().toString());
		payload.put("trace_id", log.traceId());
		payload.put("received_at", log.receivedAt().toString());
		return payload;
	}

	private byte[] envelope(NormalizedLog log, String eventType, ObjectNode payload) {
		var event = mapper.createObjectNode();
		event.put("event_id", log.eventId().toString());
		event.put("event_type", eventType);
		event.put("occurred_at", log.processedAt().toString());
		event.put("producer", "processing");
		event.put("schema_version", 1);
		event.set("payload", payload);
		return mapper.writeValueAsBytes(event);
	}

	private JsonNode metadata(String json) {
		JsonNode metadata;
		try {
			metadata = metadataReader.readTree(json);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Normalized metadata must be a JSON object");
		}
		if (metadata == null || !metadata.isObject()) {
			throw new IllegalStateException("Normalized metadata must be a JSON object");
		}
		return metadata;
	}

	private record UncertainSend(CorrelationData correlation, PublisherCallbackChannel channel) { }
}
