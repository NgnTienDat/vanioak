package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;

class AlertConsumerConfigurationTest {
	@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
	@Test void defaultDisabledCreatesNoConsumerPublisherOrConnection() {
		new ApplicationContextRunner().withUserConfiguration(AlertConsumerConfiguration.class).run(context -> {
			assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(AlertLogConsumer.class).isEmpty());
			assertTrue(context.getBeansOfType(AlertFailurePublisher.class).isEmpty());assertTrue(context.getBeansOfType(SimpleMessageListenerContainer.class).isEmpty());
		});
	}
	@Test void enabledWithoutRequiredFenceFailsBeforeAnyInfrastructureUse() {
		var connection=mock(ConnectionFactory.class);
		new ApplicationContextRunner().withUserConfiguration(AlertConsumerConfiguration.class).withBean(ConnectionFactory.class,() -> connection)
				.withBean(com.h.vanioak.modules.alert.api.AlertEngineFacade.class,() -> mock(com.h.vanioak.modules.alert.api.AlertEngineFacade.class))
				.withPropertyValues("vanioak.alert.consumer-enabled=true").run(context -> {
			assertNotNull(context.getStartupFailure());verify(connection,never()).createConnection();
		});
	}
	@Test void preparedListenerUsesExistingQueueManualOnePrefetchAndNoRecoveryOrDeclaration() {
		var connection=mock(ConnectionFactory.class);var consumer=mock(AlertLogConsumer.class);
		var container=AlertConsumerConfiguration.preparedContainer(connection,consumer);
		assertFalse(container.isAutoStartup());assertFalse(container.isRunning());assertArrayEquals(new String[]{"alert.queue"},container.getQueueNames());
		assertEquals(AcknowledgeMode.MANUAL,container.getAcknowledgeMode());assertEquals(1,ReflectionTestUtils.getField(container,"prefetchCount"));
		assertEquals(1,ReflectionTestUtils.getField(container,"concurrentConsumers"));assertEquals(false,ReflectionTestUtils.getField(container,"autoDeclare"));verifyNoInteractions(connection);
	}
	@Test void onlyExactConsumeOkRegistersItsNativeSourceChannel() {
		var consumer=mock(AlertLogConsumer.class);when(consumer.consumerTag()).thenReturn("owned-tag");
		var container=AlertConsumerConfiguration.preparedContainer(mock(ConnectionFactory.class),consumer);
		var events=(ApplicationEventPublisher)ReflectionTestUtils.getField(container,"applicationEventPublisher");var channel=mock(Channel.class);
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel),"realtime.queue","owned-tag"));
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel),"alert.queue","foreign-tag"));verify(consumer,never()).registered(any());
		events.publishEvent(new ConsumeOkEvent(new DefaultConsumer(channel),"alert.queue","owned-tag"));verify(consumer).registered(channel);
	}
	@Test void enabledAssemblyUsesDedicatedTemplateAndRemainsDisconnectedUntilStart() {
		var connection=mock(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class);
		var engine=mock(com.h.vanioak.modules.alert.api.AlertEngineFacade.class);
		var p=new AlertConsumerProperties(true,0,java.util.List.of(),java.time.Duration.ofSeconds(1),directory.resolve("alert.fence"),null);
		try(var lifecycle=new AlertConsumerConfiguration().alertConsumerLifecycle(connection,engine,p)) {
			assertFalse(lifecycle.ready());verify(connection,never()).createConnection();
			assertFalse(java.nio.file.Files.exists(p.fencePath()));
			var container=(SimpleMessageListenerContainer)ReflectionTestUtils.getField(lifecycle,"container");assertEquals(AcknowledgeMode.MANUAL,container.getAcknowledgeMode());
			var publisher=ReflectionTestUtils.getField(lifecycle,"publisher");assertNotNull(ReflectionTestUtils.getField(publisher,"rabbit"));
		}
	}
}
