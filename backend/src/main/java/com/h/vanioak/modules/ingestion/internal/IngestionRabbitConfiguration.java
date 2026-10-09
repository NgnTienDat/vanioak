package com.h.vanioak.modules.ingestion.internal;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IngestionRabbitConfiguration {
	@Bean
	public Declarables rawLogTopology() {
		var exchange = new DirectExchange(RawLogPublisher.EXCHANGE, true, false);
		var queue = new Queue(RawLogPublisher.QUEUE, true, false, false);
		Binding binding = BindingBuilder.bind(queue).to(exchange).with(RawLogPublisher.ROUTING_KEY);
		return new Declarables(exchange, queue, binding);
	}
}
