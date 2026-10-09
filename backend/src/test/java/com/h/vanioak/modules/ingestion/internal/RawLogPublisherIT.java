package com.h.vanioak.modules.ingestion.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(RawLogPublisherIT.Configuration.class)
@TestPropertySource(properties = {"spring.rabbitmq.dynamic=true", "spring.rabbitmq.publisher-confirm-type=correlated",
		"spring.rabbitmq.publisher-returns=true", "spring.rabbitmq.template.mandatory=true",
		"spring.rabbitmq.connection-timeout=5s", "spring.rabbitmq.channel-rpc-timeout=5s",
		"vanioak.ingestion.publish-confirm-timeout=5s"})
class RawLogPublisherIT {
	@TestConfiguration(proxyBeanMethods = false)
	@ImportAutoConfiguration(RabbitAutoConfiguration.class)
	@Import({RawLogPublisher.class, IngestionRabbitConfiguration.class})
	static class Configuration {
		@Bean
		static ApplicationConversionService conversionService() { return new ApplicationConversionService(); }

		@Bean
		ObjectMapper mapper() { return JsonMapper.builder().build(); }
	}

	@DynamicPropertySource
	static void connection(DynamicPropertyRegistry registry) {
		// Explicit test settings only: never fall back to deployment connection credentials.
		for (String setting : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD")) {
			String value = System.getenv("INGESTION_RABBITMQ_TEST_" + setting);
			if (value == null || value.isBlank())
				throw new IllegalStateException("Isolated RabbitMQ test settings are required");
			String property = setting.equals("VHOST") ? "virtual-host" : setting.toLowerCase(java.util.Locale.ROOT);
			registry.add("spring.rabbitmq." + property, () -> value);
		}
	}

	@Autowired
	private RawLogPublisher publisher;
	@Autowired
	private RabbitAdmin admin;
	@Autowired
	private ObjectMapper mapper;
	@MockitoSpyBean
	private RabbitTemplate rabbit;
	private final List<UUID> ownedMessages = new ArrayList<>();
	private String ownedExchange;

	@BeforeEach
	void isolatedQueueMustBeEmpty() {
		admin.initialize();
		var queue = admin.getQueueInfo(RawLogPublisher.QUEUE);
		assertNotNull(queue);
		assertEquals(0, queue.getMessageCount(), "Dedicated test raw.queue must be empty before publishing");
	}

	@Test
	void singleAndBatchArePersistentAndReachTheDocumentedQueueAfterConfirmation() {
		for (int count : new int[] {1, 3}) {
			var event = event(count);
			ownedMessages.add(event.eventId());
			publisher.publish(event);
			readOwned(event.eventId(), body -> {
				assertEquals(mapper.valueToTree(event), mapper.readTree(body));
				assertEquals(count, mapper.readTree(body).get("payload").get("logs").size());
			});
		}
	}

	@Test
	void realMandatoryReturnCannotBeMistakenForSuccessfulPublisherAck() throws Exception {
		ownedExchange = "phase3-it-unrouted-" + UUID.randomUUID();
		admin.declareExchange(new DirectExchange(ownedExchange, true, false));
		var published = new java.util.concurrent.atomic.AtomicReference<CorrelationData>();
		doAnswer(call -> {
			published.set(call.getArgument(3));
			rabbit.send(ownedExchange, RawLogPublisher.ROUTING_KEY, call.getArgument(2), call.getArgument(3));
			return null;
		}).when(rabbit).send(eq(RawLogPublisher.EXCHANGE), eq(RawLogPublisher.ROUTING_KEY), any(Message.class), any(CorrelationData.class));
		var error = assertThrows(IngestionException.class, () -> publisher.publish(event(1)));
		assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.getErrorCode());
		assertTrue(published.get().getFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).isAck());
		assertNotNull(published.get().getReturned());
		assertEquals(0, admin.getQueueInfo(RawLogPublisher.QUEUE).getMessageCount());
	}

	@AfterEach
	void cleanup() {
		reset(rabbit);
		try {
			for (UUID id : List.copyOf(ownedMessages)) readOwned(id, body -> { });
		} finally {
			if (ownedExchange != null) admin.deleteExchange(ownedExchange);
		}
	}

	private void readOwned(UUID id, java.util.function.Consumer<byte[]> assertions) {
		rabbit.execute(channel -> {
			var message = channel.basicGet(RawLogPublisher.QUEUE, false);
			assertNotNull(message, "Expected test-owned publication");
			try {
				assertEquals(id.toString(), message.getProps().getMessageId());
				assertEquals(2, message.getProps().getDeliveryMode());
				assertEquals("application/json", message.getProps().getContentType());
				assertions.accept(message.getBody());
				channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
				ownedMessages.remove(id);
			} catch (Throwable failure) {
				channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
				throw failure;
			}
			return null;
		});
	}

	private RawLogBatchEvent event(int count) {
		Instant time = Instant.now();
		List<RawLogBatchEvent.Log> logs = java.util.stream.IntStream.range(0, count).mapToObj(index ->
				new RawLogBatchEvent.Log(UUID.randomUUID(), "source-host", "INFO", "test log", time, "trace",
						Map.of("nested", List.of(1, true, Map.of("key", "value"))))).toList();
		return new RawLogBatchEvent(UUID.randomUUID(), "raw.log", time, "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), time, logs));
	}
}
