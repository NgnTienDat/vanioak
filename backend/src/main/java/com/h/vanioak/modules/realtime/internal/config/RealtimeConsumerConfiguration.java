package com.h.vanioak.modules.realtime.internal.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.backoff.FixedBackOff;

import com.h.vanioak.modules.realtime.internal.consumer.RealtimeEventDecoder;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeFailurePublisher;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeLogConsumer;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeReadiness;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeRecoveryFence;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vanioak.realtime.enabled", havingValue = "true")
public class RealtimeConsumerConfiguration {
	@Bean(destroyMethod = "close")
	RealtimeFailurePublisher realtimeFailurePublisher(ConnectionFactory connection) {
		return new RealtimeFailurePublisher(new RabbitTemplate(connection));
	}

	@Bean(destroyMethod = "close")
	RealtimeLogConsumer realtimeLogConsumer(ConnectionFactory connection, RealtimeProperties properties,
			RealtimeReadiness readiness, RealtimeService service, RealtimeFailurePublisher failures) {
		var container = new SimpleMessageListenerContainer(connection);
		container.setQueueNames("realtime.queue");
		container.setAutoDeclare(false);
		container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
		container.setPrefetchCount(1);
		container.setConcurrentConsumers(1);
		container.setMaxConcurrentConsumers(1);
		container.setExclusive(true);
		container.setBatchSize(1);
		container.setDeBatchingEnabled(false);
		container.setForceStop(true);
		container.setForceCloseChannel(true);
		container.setShutdownTimeout(5000);
		container.setConsumerStartTimeout(5000);
		container.setMissingQueuesFatal(true);
		container.setDeclarationRetries(0);
		container.setRecoveryBackOff(new FixedBackOff(0, 0));
		var consumer = new RealtimeLogConsumer(container, new RealtimeEventDecoder(), failures, service, readiness,
				new RealtimeRecoveryFence(properties.fencePath()));
		container.afterPropertiesSet();
		return consumer;
	}
}
