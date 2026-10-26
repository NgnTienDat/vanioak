package com.h.vanioak.modules.processing.internal.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.h.vanioak.modules.processing.internal.consumer.RawLogConsumer;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;
import com.h.vanioak.modules.processing.internal.publisher.ProcessingFailurePublisher;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vanioak.processing.consumer-enabled", havingValue = "true")
@EnableRabbit
public class ProcessingConsumerConfiguration {

	@Bean
	RawLogNormalizer rawLogNormalizer(ObjectMapper mapper) {
		return new RawLogNormalizer(mapper);
	}

	@Bean
	ProcessingFailurePublisher processingFailurePublisher(RabbitTemplate rabbit, ObjectMapper mapper,
			@Value("${vanioak.processing.publish-confirm-timeout}") Duration confirmTimeout,
			@Value("${vanioak.processing.retry-max-attempts}") int maxRetries,
			@Value("${vanioak.processing.retry-delays}") String retryDelays) {
		List<Duration> delays = retryDelays.isBlank() ? List.of()
				: Arrays.stream(retryDelays.split(",", -1)).map(String::trim)
						.map(DurationStyle::detectAndParse).toList();
		return new ProcessingFailurePublisher(rabbit, mapper, confirmTimeout, maxRetries, delays);
	}

	@Bean
	SimpleRabbitListenerContainerFactory processingRawListenerFactory(ConnectionFactory connection,
			@Value("${vanioak.processing.consumer-prefetch}") int prefetch) {
		if (prefetch < 1) {
			throw new IllegalArgumentException("Processing prefetch must be positive");
		}
		var factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connection);
		// RawLogNormalizer owns parsing; transport content types must not trigger Java deserialization.
		factory.setMessageConverter(new SimpleMessageConverter() {
			@Override
			public Object fromMessage(Message message) {
				return message.getBody();
			}
		});
		factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
		factory.setConcurrentConsumers(1);
		factory.setMaxConcurrentConsumers(1);
		factory.setPrefetchCount(prefetch);
		factory.setDefaultRequeueRejected(true);
		factory.setForceStop(true);
		factory.setMissingQueuesFatal(true);
		factory.setContainerCustomizer(container -> container.setShutdownTimeout(5000L));
		factory.setErrorHandler(failure -> {
			throw new ImmediateRequeueAmqpException("Raw listener delivery remains unaccounted");
		});
		return factory;
	}

	@Bean(destroyMethod = "close")
	RawLogConsumer rawLogConsumer(RawLogNormalizer normalizer, ProcessingBatchWriter batchWriter,
			ProcessedLogPublisher publisher, ProcessingFailurePublisher failures,
			@Value("${vanioak.processing.consumer-prefetch}") int prefetch) {
		return new RawLogConsumer(normalizer, batchWriter, publisher, failures, prefetch);
	}

	@Bean
	Declarables processingFailureTopology() {
		var retry = QueueBuilder.durable(ProcessingFailurePublisher.RETRY_QUEUE).quorum()
				.withArgument("x-dead-letter-strategy", "at-least-once")
				.withArgument("x-overflow", "reject-publish")
				.deadLetterExchange("raw.exchange").deadLetterRoutingKey("raw.log").build();
		var exchange = new DirectExchange(ProcessingFailurePublisher.DLQ_EXCHANGE, true, false);
		var queue = new Queue(ProcessingFailurePublisher.DLQ_QUEUE, true, false, false);
		return new Declarables(retry, exchange, queue,
				BindingBuilder.bind(queue).to(exchange).with(ProcessingFailurePublisher.DLQ_KEY));
	}
}