package com.h.vanioak.modules.processing.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import com.h.vanioak.modules.ingestion.internal.IngestionRabbitConfiguration;
import com.h.vanioak.modules.ingestion.internal.RawLogPublisher;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.config.ProcessingConsumerConfiguration;
import com.h.vanioak.modules.processing.internal.config.ProcessingRabbitConfiguration;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import com.h.vanioak.modules.processing.internal.persistence.ClickHouseLogWriter;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;
import com.h.vanioak.modules.processing.internal.publisher.ProcessingFailurePublisher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringJUnitConfig(RawLogConsumerIT.Configuration.class)
@TestPropertySource(properties = {"spring.rabbitmq.dynamic=true", "spring.rabbitmq.publisher-confirm-type=correlated",
		"spring.rabbitmq.publisher-returns=true", "spring.rabbitmq.template.mandatory=true",
		"spring.rabbitmq.connection-timeout=5s", "spring.rabbitmq.channel-rpc-timeout=5s",
		"vanioak.processing.consumer-enabled=true", "vanioak.processing.consumer-prefetch=16",
		"vanioak.processing.publish-confirm-timeout=5s", "vanioak.processing.retry-max-attempts=3",
		"vanioak.processing.retry-delays=25ms,50ms,100ms"})
class RawLogConsumerIT {

	@TestConfiguration(proxyBeanMethods = false)
	@ImportAutoConfiguration(RabbitAutoConfiguration.class)
	@Import({ProcessingConsumerConfiguration.class, ProcessingRabbitConfiguration.class,
			IngestionRabbitConfiguration.class})
	static class Configuration {
		@Bean static ApplicationConversionService conversionService() { return new ApplicationConversionService(); }
		@Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
		@Bean ProcessedLogPublisher publisher(RabbitTemplate rabbit, ObjectMapper mapper) {
			return spy(new ProcessedLogPublisher(rabbit, mapper, Duration.ofSeconds(5)));
		}
		@Bean ClickHouseProperties properties() {
			return new ClickHouseProperties(URI.create(setting("PROCESSING_CLICKHOUSE_TEST_URL")),
					setting("PROCESSING_CLICKHOUSE_TEST_USERNAME"), setting("PROCESSING_CLICKHOUSE_TEST_PASSWORD"),
					Duration.ofSeconds(5), Duration.ofSeconds(5));
		}
		@Bean(destroyMethod = "close") ClickHouseConnection clickHouseConnection(ClickHouseProperties properties) {
			return new ClickHouseConnection(properties);
		}
		@Bean ClickHouseLogWriter clickHouseLogWriter(ClickHouseConnection connection, ObjectMapper mapper) {
			return spy(new ClickHouseLogWriter(connection, mapper));
		}
		@Bean(destroyMethod = "close") ProcessingBatchWriter batchWriter(ClickHouseLogWriter writer) {
			return new ProcessingBatchWriter(writer, 1000, 2000);
		}
		@Bean static BeanPostProcessor noStartupBeforeIsolationCheck() {
			return new BeanPostProcessor() {
				@Override public Object postProcessBeforeInitialization(Object bean, String name) {
					if (bean instanceof SimpleRabbitListenerContainerFactory factory) factory.setAutoStartup(false);
					return bean;
				}
			};
		}
	}

	@DynamicPropertySource
	static void isolatedSettings(DynamicPropertyRegistry registry) {
		for (String key : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD")) {
			String value = setting("PROCESSING_RABBITMQ_TEST_" + key);
			registry.add("spring.rabbitmq." + (key.equals("VHOST") ? "virtual-host" : key.toLowerCase(Locale.ROOT)),
					() -> value);
		}
		for (String key : List.of("URL", "USERNAME", "PASSWORD")) setting("PROCESSING_CLICKHOUSE_TEST_" + key);
	}

	@Autowired private RabbitAdmin admin;
	@Autowired private RabbitTemplate rabbit;
	@Autowired private RabbitListenerEndpointRegistry registry;
	@Autowired private ProcessingFailurePublisher failures;
	@Autowired private RawLogConsumer consumer;
	@Autowired private ClickHouseLogWriter writer;
	@Autowired private ProcessedLogPublisher publisher;
	@Autowired private ClickHouseConnection clickHouse;
	@Autowired private ClickHouseProperties properties;
	@Autowired private ObjectMapper mapper;
	private final Map<String, Set<String>> owned = new HashMap<>();
	private Level previousLogLevel;

	@BeforeEach
	void verifyIsolatedPrerequisitesAndEmptyQueuesBeforeStarting() throws Exception {
		admin.initialize();
		assertTrue(Boolean.TRUE.equals(rabbit.execute(channel -> channel.getConnection().getServerProperties().get("version")
				.toString().startsWith("4.2."))), "Approved RabbitMQ 4.2 prerequisite required");
		for (String name : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue")) {
			var info = admin.getQueueInfo(name);
			assertNotNull(info);
			assertEquals(0, info.getMessageCount(), "Dedicated queues must start empty");
			assertEquals(0, info.getConsumerCount(), "Dedicated queues must have no other consumers");
			owned.put(name, new HashSet<>());
		}
		clickHouse.checkConnectivity(); // The unchanged logs table must already be provisioned.
		assertNotNull(query("SELECT event_id FROM logs LIMIT 0 FORMAT JSONEachRow"));
		var logger = (Logger) LoggerFactory.getLogger(MessagingMessageListenerAdapter.class);
		previousLogLevel = logger.getLevel();
		logger.setLevel(Level.OFF);
		reset(writer);
		reset(publisher);
	}

	@Test
	void fullPipelinePersistsThenRoutesSingleLogEventsAndAccountsOriginalDelivery() throws Exception {
		var raw = event(List.of("INFO", "WARN", "ERROR", "CRITICAL"));
		own(raw);
		registry.start();
		new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(raw);
		for (var log : raw.payload().logs()) {
			var processed = receiveOwned("realtime.queue");
			assertEquals(log.eventId().toString(), processed.get("event_id").asString());
			assertEquals("processed.log", processed.get("event_type").asString());
			var payload = processed.get("payload");
			assertEquals(raw.payload().applicationId().toString(), payload.get("application_id").asString());
			assertEquals(raw.payload().environmentId().toString(), payload.get("environment_id").asString());
			assertEquals(raw.payload().receivedAt().toString(), payload.get("received_at").asString());
			assertEquals(log.timestamp().toString(), payload.get("timestamp").asString());
			assertEquals(payload.get("processed_at"), processed.get("occurred_at"));
			var stored = mapper.readTree(query("SELECT * FROM logs WHERE event_id='" + log.eventId() + "' LIMIT 1 FORMAT JSONEachRow"));
			assertEquals(11, stored.size());
			assertEquals(log.eventId().toString(), stored.get("event_id").asString());
			assertEquals(raw.payload().applicationId().toString(), stored.get("application_id").asString());
			assertEquals(raw.payload().environmentId().toString(), stored.get("environment_id").asString());
			assertEquals(log.hostIp(), stored.get("host_ip").asString());
			assertEquals(log.level(), stored.get("level").asString());
			assertEquals(log.message(), stored.get("message").asString());
			assertEquals(log.traceId(), stored.get("trace_id").asString());
			assertEquals(mapper.valueToTree(log.metadata()), mapper.readTree(stored.get("metadata_json").asString()));
			assertEquals(log.timestamp().truncatedTo(ChronoUnit.MILLIS), storedInstant(stored, "timestamp"));
			assertEquals(raw.payload().receivedAt().truncatedTo(ChronoUnit.MILLIS), storedInstant(stored, "received_at"));
			assertEquals(Instant.parse(payload.get("processed_at").asString()).truncatedTo(ChronoUnit.MILLIS),
					storedInstant(stored, "processed_at"));
			if (List.of("ERROR", "CRITICAL").contains(log.level())) {
				var critical = receiveOwned("alert.queue");
				assertEquals(log.eventId().toString(), critical.get("event_id").asString());
				assertEquals(8, critical.get("payload").size());
			}
		}
		stopAfterAccounting();
		assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
	}

	@Test
	void prefetchSixteenFlushesSixteenSingleLogsAndKeepsSeventeenthReadyUntilPersistence() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var size = new AtomicInteger();
		doAnswer(call -> {
			if (entered.getCount() != 0) {
				size.set(((List<?>) call.getArgument(0)).size());
				entered.countDown();
				assertTrue(release.await(15, TimeUnit.SECONDS));
			}
			return call.callRealMethod();
		}).when(writer).write(anyList());
		for (int index = 0; index < 17; index++) {
			var raw = event(List.of("INFO"));
			own(raw);
			new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(raw);
		}
		registry.start();
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS), "Two-second flush must progress below threshold");
			assertEquals(16, size.get());
			assertEquals(1, admin.getQueueInfo("raw.queue").getMessageCount());
			assertEquals(0, admin.getQueueInfo("realtime.queue").getMessageCount());
			release.countDown();
			for (int index = 0; index < 17; index++) receiveOwned("realtime.queue");
			stopAfterAccounting();
			assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
		} finally { release.countDown(); }
	}

	@Test
	void persistedMultiWindowBatchRetainsRawCreditUntilRequiredPublication() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		doAnswer(call -> {
			if (entered.getCount() != 0) {
				entered.countDown();
				assertTrue(release.await(15, TimeUnit.SECONDS));
			}
			return call.callRealMethod();
		}).when(publisher).publishBatch(anyList());
		var batch = event(java.util.Collections.nCopies(70, "ERROR"));
		own(batch);
		new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(batch);
		for (int index = 0; index < 16; index++) {
			var raw = event(List.of("INFO"));
			own(raw);
			new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(raw);
		}
		registry.start();
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			assertEquals(70, mapper.readTree(query("SELECT uniqExact(event_id) AS ids FROM logs WHERE application_id='"
					+ batch.payload().applicationId() + "' FORMAT JSONEachRow")).get("ids").asInt());
			assertEquals(1, admin.getQueueInfo("raw.queue").getMessageCount(),
					"The seventeenth raw delivery must stay ready while required publication is gated");
			assertEquals(0, admin.getQueueInfo("realtime.queue").getMessageCount());
			assertEquals(0, admin.getQueueInfo("alert.queue").getMessageCount());
			release.countDown();
			var processed = new HashSet<String>();
			var critical = new HashSet<String>();
			for (int index = 0; index < 86; index++) processed.add(receiveOwned("realtime.queue").get("event_id").asString());
			for (int index = 0; index < 70; index++) critical.add(receiveOwned("alert.queue").get("event_id").asString());
			assertEquals(owned.get("realtime.queue"), processed);
			assertEquals(owned.get("alert.queue"), critical);
			assertTrue(processed.containsAll(critical));
			stopAfterAccounting();
			assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
		} finally {
			release.countDown();
		}
	}

	@Test
	void channelRecoveryBeforeRequiredPublicationRedeliversAlreadyPersistedIdentity() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var calls = new AtomicInteger();
		doAnswer(call -> {
			if (calls.getAndIncrement() == 0) {
				entered.countDown();
				assertTrue(release.await(15, TimeUnit.SECONDS));
			}
			return call.callRealMethod();
		}).when(publisher).publishBatch(org.mockito.ArgumentMatchers.anyList());
		var raw = event(List.of("INFO"));
		own(raw);
		registry.start();
		try {
			new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(raw);
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			assertFalse(query("SELECT event_id FROM logs WHERE event_id='" + raw.payload().logs().getFirst().eventId()
					+ "' FORMAT JSONEachRow").isBlank());
			registry.stop(); // Required publication is gated: the original must remain unacknowledged.
			((CachingConnectionFactory) rabbit.getConnectionFactory()).resetConnection();
			until(() -> admin.getQueueInfo("raw.queue").getMessageCount() == 1);
			release.countDown();
			registry.start();
			until(() -> calls.get() >= 2);
			receiveOwned("realtime.queue");
			stopAfterAccounting();
		} finally { release.countDown(); }
	}

	private Instant storedInstant(JsonNode row, String field) {
		return Instant.parse(row.get(field).asString().replace(' ', 'T') + "Z");
	}

	@Test
	void realQuorumTtlForwardingPreservesRawBytesAndRetryCounter() throws Exception {
		var raw = event(List.of("INFO"));
		own(raw);
		var props = new MessageProperties();
		props.setMessageId(raw.eventId().toString());
		props.setContentType("application/json");
		byte[] bytes = mapper.writeValueAsBytes(raw);
		failures.retry(new Message(bytes, props));
		JsonNode forwarded = receiveOwned("raw.queue", 1);
		assertEquals(mapper.readTree(bytes), forwarded);
		assertEquals(0, admin.getQueueInfo("raw.retry.queue").getMessageCount());
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PROCESSING_DEFAULT_RETRY_TEST_APPROVED", matches = "true")
	void realDefaultRetryDelaysPreserveExactBytesThroughAllThreeForwardings() throws Exception {
		var raw = event(List.of("INFO"));
		own(raw);
		byte[] bytes = mapper.writeValueAsBytes(raw);
		var defaultDelays = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120));
		var publisher = new ProcessingFailurePublisher(rabbit, mapper, Duration.ofSeconds(5), 3, defaultDelays);
		for (int count = 0; count < 3; count++) {
			var properties = new MessageProperties();
			properties.setMessageId(raw.eventId().toString());
			properties.setHeader("x-processing-retry-count", count);
			long started = System.nanoTime();
			publisher.retry(new Message(bytes, properties));
			int expectedCount = count + 1;
			Duration delay = defaultDelays.get(count);
			rabbit.execute(channel -> {
				long deadline = System.nanoTime() + delay.plusSeconds(15).toNanos();
				while (System.nanoTime() < deadline) {
					var message = channel.basicGet("raw.queue", false);
					if (message == null) { Thread.sleep(20); continue; }
					try {
						assertEquals(raw.eventId().toString(), message.getProps().getMessageId());
						org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, message.getBody());
						assertEquals(expectedCount, message.getProps().getHeaders().get("x-processing-retry-count"));
						assertTrue(System.nanoTime() - started >= delay.toNanos(), "Retry wait must not be shorter than TTL");
						channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
						return null;
					} catch (Throwable failure) {
						channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
						throw failure;
					}
				}
				throw new AssertionError("Default retry forwarding deadline exceeded");
			});
		}
	}

	@Test
	void permanentBatchAndExhaustedControlledPersistenceFailuresReachConfirmedDlq() throws Exception {
		var invalid = event(List.of("INFO", "ERROR"));
		own(invalid);
		var json = mapper.readTree(mapper.writeValueAsBytes(invalid));
		((ObjectNode) json.get("payload").get("logs").get(1)).put("level", "invalid");
		var props = new MessageProperties();
		props.setMessageId(invalid.eventId().toString());
		props.setContentType("application/json");
		var attempts = new AtomicInteger();
		// Controlled storage failure only; TTL, confirms, routing and raw ACK are real RabbitMQ behavior.
		doAnswer(call -> {
			attempts.incrementAndGet();
			throw new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE);
		}).when(writer).write(anyList());
		registry.start();
		sendRaw(new Message(mapper.writeValueAsBytes(json), props));
		var permanent = receiveOwned("dead-letter.queue");
		assertEquals("INVALID_RAW_BATCH", permanent.get("payload").get("reason").asString());
		assertEquals(json, permanent.get("payload").get("original_message"));
		assertEquals(0, attempts.get());
		var retry = event(List.of("WARN"));
		own(retry);
		new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(retry);
		var exhausted = receiveOwned("dead-letter.queue");
		assertEquals("PROCESSING_RETRY_EXHAUSTED", exhausted.get("payload").get("reason").asString());
		assertEquals(mapper.readTree(mapper.writeValueAsBytes(retry)), exhausted.get("payload").get("original_message"));
		assertEquals(4, attempts.get());
		stopAfterAccounting();
		assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
	}

	@Test
	void stoppingBeforePersistenceReturnsOriginalForRedeliveryWithStableIdentity() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var attempts = new AtomicInteger();
		doAnswer(call -> {
			if (attempts.getAndIncrement() == 0) {
				entered.countDown();
				assertTrue(release.await(15, TimeUnit.SECONDS));
			}
			return call.callRealMethod();
		}).when(writer).write(anyList());
		var raw = event(List.of("INFO"));
		own(raw);
		registry.start();
		try {
			new RawLogPublisher(rabbit, mapper, Duration.ofSeconds(5)).publish(raw);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			registry.stop();
			until(() -> admin.getQueueInfo("raw.queue").getMessageCount() == 1);
			release.countDown();
			registry.start();
			receiveOwned("realtime.queue");
			until(() -> attempts.get() >= 2);
			stopAfterAccounting();
			assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
		} finally {
			release.countDown();
		}
	}

	private void own(RawLogBatchEvent event) {
		for (String queue : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue"))
			owned.get(queue).add(event.eventId().toString());
		for (var log : event.payload().logs()) {
			owned.get("realtime.queue").add(log.eventId().toString());
			if (List.of("ERROR", "CRITICAL").contains(log.level())) owned.get("alert.queue").add(log.eventId().toString());
		}
	}

	private JsonNode receiveOwned(String queue) { return receiveOwned(queue, null); }

	private JsonNode receiveOwned(String queue, Integer retryCount) {
		return rabbit.execute(channel -> {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			do {
				var received = channel.basicGet(queue, false);
				if (received != null) {
					try {
						assertTrue(owned.get(queue).contains(received.getProps().getMessageId()), "Unexpected message; requeue");
						assertEquals(2, received.getProps().getDeliveryMode());
						if (retryCount != null) {
							assertEquals(retryCount, received.getProps().getHeaders().get("x-processing-retry-count"));
						}
						JsonNode body = mapper.readTree(received.getBody());
						channel.basicAck(received.getEnvelope().getDeliveryTag(), false);
						return body;
					} catch (Throwable failure) {
						channel.basicNack(received.getEnvelope().getDeliveryTag(), false, true);
						throw failure;
					}
				}
				Thread.sleep(10); // Bounded polling on the explicitly isolated broker.
			} while (System.nanoTime() < deadline);
			throw new AssertionError("Timed out waiting for test-owned message");
		});
	}

	private void stopAfterAccounting() throws Exception {
		until(() -> {
			synchronized (ReflectionTestUtils.getField(consumer, "lifecycle")) {
				return ((Set<?>) ReflectionTestUtils.getField(consumer, "active")).isEmpty();
			}
		});
		registry.stop();
	}

	private void sendRaw(Message message) throws Exception {
		var correlation = new org.springframework.amqp.rabbit.connection.CorrelationData();
		rabbit.send("raw.exchange", "raw.log", message, correlation);
		assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).ack());
		assertEquals(null, correlation.getReturned());
	}

	private String query(String sql) throws Exception {
		String credentials = properties.username() + ":" + properties.password();
		var request = HttpRequest.newBuilder(properties.url()).timeout(properties.requestTimeout())
				.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
				.POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8)).build();
		try (var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build()) {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, response.statusCode(), "Isolated ClickHouse query failed");
			return response.body();
		}
	}

	private void until(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
		assertTrue(condition.getAsBoolean(), "Isolated processing condition did not complete");
	}

	private RawLogBatchEvent event(List<String> levels) {
		Instant now = Instant.now();
		var logs = levels.stream().map(level -> new RawLogBatchEvent.Log(UUID.randomUUID(), "127.0.0.1", level,
				"Phase 4 isolated test", now.minusMillis(10), UUID.randomUUID().toString(), Map.<String, Object>of("nested", List.of(1, true)))).toList();
		return new RawLogBatchEvent(UUID.randomUUID(), "raw.log", now, "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, logs));
	}

	@AfterEach
	void stopAndCleanOnlyTestOwnedMessages() {
		registry.stop();
		for (var entry : owned.entrySet()) {
			if (List.of("raw.queue", "raw.retry.queue").contains(entry.getKey())) {
				assertEquals(0, admin.getQueueInfo(entry.getKey()).getMessageCount(),
						"Unresolved raw work is retained for inspection; cleanup must not ACK it");
				continue;
			}
			rabbit.execute(channel -> {
				for (int count = 0; count < 100; count++) {
					var received = channel.basicGet(entry.getKey(), false);
					if (received == null) return null;
					if (!entry.getValue().contains(received.getProps().getMessageId())) {
						channel.basicNack(received.getEnvelope().getDeliveryTag(), false, true);
						throw new IllegalStateException("Unexpected message in isolated queue; requeued");
					}
					channel.basicAck(received.getEnvelope().getDeliveryTag(), false);
				}
				throw new IllegalStateException("Unexpected test backlog");
			});
		}
		reset(writer);
		reset(publisher);
		((Logger) LoggerFactory.getLogger(MessagingMessageListenerAdapter.class)).setLevel(previousLogLevel);
	}

	private static String setting(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) throw new IllegalStateException("Explicit isolated RabbitMQ and ClickHouse settings required");
		return value;
	}
}
