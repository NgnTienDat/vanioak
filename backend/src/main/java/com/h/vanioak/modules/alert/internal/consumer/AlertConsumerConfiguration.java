package com.h.vanioak.modules.alert.internal.consumer;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStoppedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.backoff.FixedBackOff;
import com.rabbitmq.client.DefaultConsumer;
import org.springframework.core.env.Environment;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;

@Configuration(proxyBeanMethods=false)
public class AlertConsumerConfiguration {
	@Bean
	AlertConsumerProperties alertConsumerProperties(Environment environment) {
		return AlertConsumerProperties.from(environment);
	}
	@Bean(destroyMethod="close")
	@ConditionalOnExpression("${vanioak.alert.consumer-enabled:${ALERT_CONSUMER_ENABLED:false}}")
	AlertConsumerLifecycle alertConsumerLifecycle(ConnectionFactory connection,AlertEngineFacade engine,AlertConsumerProperties properties) {
		return new AlertConsumerLifecycle(connection,engine,properties);
	}
	static SimpleMessageListenerContainer preparedContainer(ConnectionFactory connection,AlertLogConsumer consumer) {
		var container=new SimpleMessageListenerContainer(connection);
		container.setAutoStartup(false);container.setAutoDeclare(false);container.setQueueNames("alert.queue");
		container.setAcknowledgeMode(AcknowledgeMode.MANUAL);container.setPrefetchCount(1);
		container.setConcurrentConsumers(1);container.setMaxConcurrentConsumers(1);
		container.setBatchSize(1);container.setDeBatchingEnabled(false);
		container.setRecoveryBackOff(new FixedBackOff(0,0));container.setDeclarationRetries(0);container.setMissingQueuesFatal(true);
		container.setForceCloseChannel(true);container.setForceStop(true);container.setShutdownTimeout(5000);
		container.setConsumerStartTimeout(5000);
		container.setConsumerTagStrategy(queue -> consumer.consumerTag());container.setMessageListener(consumer);
		container.setApplicationEventPublisher(event -> {
			if(event instanceof ConsumeOkEvent ok && "alert.queue".equals(ok.getQueue()) && consumer.consumerTag().equals(ok.getConsumerTag())
					&& ok.getSource() instanceof DefaultConsumer nativeConsumer) consumer.registered(nativeConsumer.getChannel());
			else if(event instanceof ListenerContainerConsumerFailedEvent || event instanceof AsyncConsumerStoppedEvent) consumer.stop();
		});
		container.setErrorHandler(error -> consumer.stop());
		return container;
	}
}
