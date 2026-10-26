package com.h.vanioak.modules.processing.internal.publisher;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.exc.MismatchedInputException;

@Slf4j
public class ProcessingFailurePublisher {

	public static final String RETRY_QUEUE = "raw.retry.queue";
	static final String RETRY_COUNT = "x-processing-retry-count";
	public static final String DLQ_EXCHANGE = "dead-letter.exchange";
	public static final String DLQ_QUEUE = "dead-letter.queue";
	public static final String DLQ_KEY = "dead-letter.log";
	public static final String INVALID_BATCH = "INVALID_RAW_BATCH";
	public static final String INVALID_RETRY = "INVALID_RETRY_METADATA";
	static final String EXHAUSTED = "PROCESSING_RETRY_EXHAUSTED";
	private final RabbitTemplate rabbit;
	private final ObjectMapper mapper;
	private final ObjectReader reader;
	private final Duration confirmTimeout;
	private final List<Duration> delays;
	private final int maxRetries;
	private final Clock clock;

	public ProcessingFailurePublisher(RabbitTemplate rabbit, ObjectMapper mapper, Duration confirmTimeout,
			int maxRetries, List<Duration> delays) {
		this(rabbit, mapper, confirmTimeout, maxRetries, delays, Clock.systemUTC());
	}

	ProcessingFailurePublisher(RabbitTemplate rabbit, ObjectMapper mapper, Duration confirmTimeout,
			int maxRetries, List<Duration> delays, Clock clock) {
		if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.toMillis() < 1) {
			throw new IllegalArgumentException("Publish confirmation timeout must be positive");
		}
		if (maxRetries < 0 || delays == null || delays.size() != maxRetries
				|| delays.stream().anyMatch(delay -> delay == null || delay.isNegative() || delay.toMillis() < 1)) {
			throw new IllegalArgumentException("Each retry requires one positive delay");
		}
		this.rabbit = rabbit;
		this.mapper = mapper;
		this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
		this.confirmTimeout = confirmTimeout;
		this.maxRetries = maxRetries;
		this.delays = List.copyOf(delays);
		this.clock = clock;
	}

	public int retryCount(Message message) {
		var headers = message.getMessageProperties().getHeaders();
		if (!headers.containsKey(RETRY_COUNT)) {
			return 0;
		}
		Object value = headers.get(RETRY_COUNT);
		if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
			return -1;
		}
		long count = ((Number) value).longValue();
		return count >= 0 && count <= maxRetries ? (int) count : -1;
	}

	public Duration cooldown() {
		return delays.isEmpty() ? Duration.ofSeconds(5) : delays.getFirst();
	}

	public void retry(Message original) {
		int count = retryCount(original);
		if (count < 0 || count == maxRetries) {
			deadLetter(original, count < 0 ? INVALID_RETRY : EXHAUSTED);
			return;
		}
		var source = original.getMessageProperties();
		var properties = properties(source.getMessageId());
		properties.setContentType(source.getContentType());
		properties.setContentEncoding(source.getContentEncoding());
		properties.setCorrelationId(source.getCorrelationId());
		properties.setHeader(RETRY_COUNT, count + 1);
		properties.setExpiration(Long.toString(delays.get(count).toMillis()));
		send("", RETRY_QUEUE, new Message(original.getBody(), properties));
	}

	public void deadLetter(Message original, String reason) {
		if (!List.of(INVALID_BATCH, INVALID_RETRY, EXHAUSTED).contains(reason)) {
			throw new IllegalArgumentException("Unsupported Processing failure reason");
		}
		JsonNode source = originalObject(original.getBody());
		UUID id = source == null ? null : uuid(source.get("event_id"));
		if (id == null) {
			id = uuid(mapper.getNodeFactory().stringNode(original.getMessageProperties().getMessageId()));
		}
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (source == null) {
			var wrapper = mapper.createObjectNode();
			wrapper.put("body_base64", Base64.getEncoder().encodeToString(original.getBody()));
			source = wrapper;
		}
		String failedAt = clock.instant().toString();
		var payload = mapper.createObjectNode();
		payload.put("event_id", id.toString());
		payload.put("reason", reason);
		payload.put("field", "$");
		payload.set("original_message", source);
		payload.put("failed_at", failedAt);
		var event = mapper.createObjectNode();
		event.put("event_id", id.toString());
		event.put("event_type", DLQ_KEY);
		event.put("occurred_at", failedAt);
		event.put("producer", "processing");
		event.put("schema_version", 1);
		event.set("payload", payload);
		send(DLQ_EXCHANGE, DLQ_KEY, new Message(mapper.writeValueAsBytes(event), properties(id.toString())));
	}

	private MessageProperties properties(String id) {
		var properties = new MessageProperties();
		properties.setMessageId(id);
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		return properties;
	}

	private JsonNode originalObject(byte[] bytes) {
		try {
			JsonNode node = reader.readTree(bytes);
			return node != null && node.isObject() ? node : null;
		} catch (StreamReadException | StreamConstraintsException | MismatchedInputException exception) {
			return null;
		}
	}

	private UUID uuid(JsonNode node) {
		if (node == null || !node.isString()) {
			return null;
		}
		try {
			String value = node.asString();
			UUID id = UUID.fromString(value);
			return id.toString().equalsIgnoreCase(value) ? id : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private void send(String exchange, String key, Message message) {
		var correlation = new CorrelationData();
		try {
			rabbit.send(exchange, key, message, correlation);
			var confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!confirm.ack() || correlation.getReturned() != null) {
				throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
		} catch (AmqpException | TimeoutException | ExecutionException | CancellationException exception) {
			log.warn("Processing failure transfer unconfirmed: route={}, failure={}", key,
					exception.getClass().getSimpleName());
			throw new ProcessingException(ErrorCode.PUBLICATION_UNAVAILABLE);
		}
	}
}