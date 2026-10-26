package com.h.vanioak.modules.processing.internal.config;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcessingRabbitConfiguration {

	@Bean
	public Declarables downstreamLogTopology() {
		var processedExchange = new DirectExchange(ProcessedLogPublisher.PROCESSED_EXCHANGE, true, false);
		var realtimeQueue = new Queue(ProcessedLogPublisher.PROCESSED_QUEUE, true, false, false);
		var criticalExchange = new DirectExchange(ProcessedLogPublisher.CRITICAL_EXCHANGE, true, false);
		var alertQueue = new Queue(ProcessedLogPublisher.CRITICAL_QUEUE, true, false, false);
		return new Declarables(processedExchange, realtimeQueue,
				BindingBuilder.bind(realtimeQueue).to(processedExchange).with(ProcessedLogPublisher.PROCESSED_ROUTING_KEY),
				criticalExchange, alertQueue,
				BindingBuilder.bind(alertQueue).to(criticalExchange).with(ProcessedLogPublisher.CRITICAL_ROUTING_KEY));
	}
}
