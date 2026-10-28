package com.h.vanioak.modules.realtime.internal.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.modules.realtime.internal.consumer.RealtimeFailurePublisher;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeLogConsumer;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeReadiness;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;

class RealtimeConsumerConfigurationTest {
	@TempDir Path directory;
	@Test void disabledByDefaultCreatesNoConsumerOrInfrastructureDependency() {
		new ApplicationContextRunner().withUserConfiguration(RealtimeConsumerConfiguration.class).run(context -> {
			assertEquals(0, context.getBeansOfType(RealtimeLogConsumer.class).size());
			assertEquals(0, context.getBeansOfType(RealtimeFailurePublisher.class).size());
		});
	}
	@Test void manualExclusiveSinglePrefetchAndNoDeclarationWithoutStarting() {
		var connection = mock(ConnectionFactory.class);
		try (var consumer = new RealtimeConsumerConfiguration().realtimeLogConsumer(connection,
				new RealtimeProperties(true, List.of(), directory.resolve("fence")), new RealtimeReadiness(),
				mock(RealtimeService.class), mock(RealtimeFailurePublisher.class))) {
			var container = (SimpleMessageListenerContainer) ReflectionTestUtils.getField(consumer, "container");
			assertNotNull(container); assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
			assertArrayEquals(new String[] {"realtime.queue"}, container.getQueueNames());
			assertEquals(1, ReflectionTestUtils.getField(container, "prefetchCount"));
			assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
			assertEquals(true, ReflectionTestUtils.getField(container, "exclusive"));
			assertEquals(false, ReflectionTestUtils.getField(container, "autoDeclare"));
			verify(connection, never()).createConnection();
		}
	}

	@Test void modulePropertiesBindCanonicalConstructorAndRemainNotReadyWithoutRegistration() {
		new ApplicationContextRunner().withUserConfiguration(RealtimeConfiguration.class)
				.withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
				.withPropertyValues("vanioak.realtime.enabled=true", "vanioak.realtime.fence-path=" + directory.resolve("fence"))
				.run(context -> {
					var properties = context.getBean(RealtimeProperties.class);
					assertTrue(properties.enabled()); assertEquals(directory.resolve("fence"), properties.fencePath());
					assertFalse(context.getBean(RealtimeService.class).ready());
					assertFalse(java.nio.file.Files.exists(directory.resolve("fence")));
				});
	}
}
