package com.h.vanioak.modules.ingestion.internal;

import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class RawLogPublisher {
	static final String EXCHANGE = "raw.exchange";
	static final String QUEUE = "raw.queue";
	static final String ROUTING_KEY = "raw.log";
	private final RabbitTemplate rabbit;
	private final ObjectMapper mapper;
	private final Duration confirmTimeout;

	public RawLogPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
			@Value("${vanioak.ingestion.publish-confirm-timeout}") Duration confirmTimeout) {
		if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.toMillis() < 1)
			throw new IllegalArgumentException("Publish confirmation timeout must be positive");
		this.rabbit = rabbit;
		this.mapper = mapper;
		this.confirmTimeout = confirmTimeout;
	}

	public void publish(RawLogBatchEvent event) {
		byte[] body = mapper.writeValueAsBytes(event);
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(event.eventId().toString());
		var correlation = new CorrelationData(event.eventId().toString());
		try {
			rabbit.send(EXCHANGE, ROUTING_KEY, new Message(body, properties), correlation);
			var confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!confirm.isAck() || correlation.getReturned() != null) {
				log.warn("Raw publication rejected or returned: eventId={}", event.eventId());
				throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
		} catch (AmqpException | TimeoutException | ExecutionException | CancellationException ex) {
			log.warn("Raw publication unavailable: eventId={}, failure={}", event.eventId(), ex.getClass().getSimpleName());
			throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
		}
	}
}
