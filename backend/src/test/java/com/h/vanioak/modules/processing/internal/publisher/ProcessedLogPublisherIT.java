package com.h.vanioak.modules.processing.internal.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.h.vanioak.modules.processing.internal.config.ProcessingRabbitConfiguration;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(ProcessedLogPublisherIT.Configuration.class)
@TestPropertySource(properties = {"spring.rabbitmq.dynamic=true", "spring.rabbitmq.publisher-confirm-type=correlated",
		"spring.rabbitmq.publisher-returns=true", "spring.rabbitmq.template.mandatory=true",
		"spring.rabbitmq.connection-timeout=5s", "spring.rabbitmq.channel-rpc-timeout=5s",
		"vanioak.processing.publish-confirm-timeout=5s"})
class ProcessedLogPublisherIT {

	@TestConfiguration(proxyBeanMethods = false)
	@ImportAutoConfiguration(RabbitAutoConfiguration.class)
	@Import({ProcessedLogPublisher.class, ProcessingRabbitConfiguration.class})
	static class Configuration {
		@Bean
		static ApplicationConversionService conversionService() { return new ApplicationConversionService(); }

		@Bean
		ObjectMapper mapper() { return JsonMapper.builder().build(); }
	}

	@DynamicPropertySource
	static void isolatedConnection(DynamicPropertyRegistry registry) {
		for (String setting : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD")) {
			String value = System.getenv("PROCESSING_RABBITMQ_TEST_" + setting);
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("Explicit isolated RabbitMQ test settings are required");
			}
			String property = setting.equals("VHOST") ? "virtual-host" : setting.toLowerCase(Locale.ROOT);
			registry.add("spring.rabbitmq." + property, () -> value);
		}
	}

	@Autowired
	private ProcessedLogPublisher publisher;
	@Autowired
	private RabbitAdmin admin;
	@Autowired
	private RabbitTemplate rabbit;
	@Autowired
	private ObjectMapper mapper;
	private final Map<String, Set<String>> ownedMessages = new HashMap<>(Map.of(
			"realtime.queue", new HashSet<>(), "alert.queue", new HashSet<>()));

	@BeforeEach
	void dedicatedQueuesMustBeEmptyWithoutConsumers() {
		admin.initialize();
		for (String name : ownedMessages.keySet()) {
			var queue = admin.getQueueInfo(name);
			assertNotNull(queue);
			assertEquals(0, queue.getMessageCount(), "Dedicated test queue must start empty");
			assertEquals(0, queue.getConsumerCount(), "Dedicated test queue must have no active consumers");
		}
	}

	@Test
	void confirmedPersistentSingleLogEventsReachFixedProductionRoutesForAllLevels() {
		for (String level : List.of("INFO", "WARN", "ERROR", "CRITICAL")) {
			Instant time = Instant.now();
			var log = new NormalizedLog(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", level,
					"Phase 3: lỗi \"quoted\"\nnext line", time, UUID.randomUUID().toString(),
					"{\"nested\":[1,true,null]}", time.plusNanos(1), time.plusNanos(2));
			boolean critical = level.equals("ERROR") || level.equals("CRITICAL");
			ownedMessages.get("realtime.queue").add(log.eventId().toString());
			if (critical) {
				ownedMessages.get("alert.queue").add(log.eventId().toString());
			}
			publisher.publish(log);
			inspectOwned("realtime.queue", "processed.log", log);
			if (critical) {
				inspectOwned("alert.queue", "critical.log", log);
			}
			assertEquals(0, admin.getQueueInfo("realtime.queue").getMessageCount());
			assertEquals(0, admin.getQueueInfo("alert.queue").getMessageCount());
		}
	}

	@Test
	void realMandatoryUnroutablePublicationsReturnBeforeNativeAckCompletion() throws Exception {
		for (String exchange : List.of(ProcessedLogPublisher.PROCESSED_EXCHANGE, ProcessedLogPublisher.CRITICAL_EXCHANGE)) {
			String routingKey = "unroutable-it-" + UUID.randomUUID();
			var properties = new MessageProperties();
			properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
			properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			properties.setMessageId(UUID.randomUUID().toString());
			var message = new Message(mapper.writeValueAsBytes(Map.of("test", "mandatory-return")), properties);
			var correlation = new CorrelationData();
			assertTrue(rabbit.isMandatoryFor(message));
			rabbit.send(exchange, routingKey, message, correlation);
			assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).ack());
			var returned = correlation.getReturned();
			assertNotNull(returned, "Native ACK alone must not hide an unroutable mandatory message");
			assertEquals(312, returned.getReplyCode());
			assertEquals(exchange, returned.getExchange());
			assertEquals(routingKey, returned.getRoutingKey());
			assertEquals(properties.getMessageId(), returned.getMessage().getMessageProperties().getMessageId());
		}
		for (String queue : ownedMessages.keySet()) assertEquals(0, admin.getQueueInfo(queue).getMessageCount());
	}

	private void inspectOwned(String queue, String type, NormalizedLog log) {
		rabbit.execute(channel -> {
			var message = channel.basicGet(queue, false);
			assertNotNull(message, "Expected test-owned publication after confirmation");
			try {
				assertEquals(log.eventId().toString(), message.getProps().getMessageId());
				assertEquals(2, message.getProps().getDeliveryMode());
				assertEquals("application/json", message.getProps().getContentType());
				assertEquals(type.equals("processed.log") ? "processed.exchange" : "critical.exchange",
						message.getEnvelope().getExchange());
				assertEquals(type, message.getEnvelope().getRoutingKey());
				var event = mapper.readTree(message.getBody());
				assertEquals(6, event.size());
				assertEquals(log.eventId().toString(), event.get("event_id").asString());
				assertEquals(type, event.get("event_type").asString());
				assertEquals(log.processedAt().toString(), event.get("occurred_at").asString());
				assertEquals("processing", event.get("producer").asString());
				assertEquals(1, event.get("schema_version").asInt());
				var payload = event.get("payload");
				assertEquals(log.eventId().toString(), payload.get("event_id").asString());
				assertEquals(log.applicationId().toString(), payload.get("application_id").asString());
				assertEquals(log.environmentId().toString(), payload.get("environment_id").asString());
				assertEquals(log.level(), payload.get("level").asString());
				assertEquals(log.message(), payload.get("message").asString());
				assertEquals(log.timestamp().toString(), payload.get("timestamp").asString());
				assertEquals(log.traceId(), payload.get("trace_id").asString());
				assertEquals(log.receivedAt().toString(), payload.get("received_at").asString());
				assertEquals(type.equals("processed.log") ? 11 : 8, payload.size());
				if (type.equals("processed.log")) {
					assertEquals(log.hostIp(), payload.get("host_ip").asString());
					assertTrue(payload.get("metadata").isObject());
					assertEquals(mapper.readTree(log.metadataJson()), payload.get("metadata"));
					assertEquals(log.processedAt().toString(), payload.get("processed_at").asString());
				}
				channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
				ownedMessages.get(queue).remove(log.eventId().toString());
			} catch (Throwable failure) {
				channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
				throw failure;
			}
			return null;
		});
	}

	@AfterEach
	void cleanupOnlyTestOwnedDownstreamMessages() {
		for (var entry : ownedMessages.entrySet()) {
			rabbit.execute(channel -> {
				while (!entry.getValue().isEmpty()) {
					var message = channel.basicGet(entry.getKey(), false);
					if (message == null) {
						break;
					}
					if (!entry.getValue().contains(message.getProps().getMessageId())) {
						channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
						throw new IllegalStateException("Unexpected message in isolated test queue; requeued");
					}
					channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
					entry.getValue().remove(message.getProps().getMessageId());
				}
				return null;
			});
		}
	}
}
