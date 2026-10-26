package com.h.vanioak.modules.processing.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.h.vanioak.BackendApplication;
import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.modules.processing.internal.consumer.RawLogConsumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application.properties", properties = {
		"vanioak.processing.consumer-enabled=true", "vanioak.processing.consumer-prefetch=16",
		"logging.level.org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter=OFF"})
@Import(ProcessingHttpPipelineIT.Startup.class)
class ProcessingHttpPipelineIT {
	private static final String ADMIN = "processing-test-" + UUID.randomUUID();
	private static final String PASSWORD = UUID.randomUUID().toString();

	@TestConfiguration(proxyBeanMethods = false)
	static class Startup {
		@Bean static BeanPostProcessor deferConsumerUntilPreflight() {
			return new BeanPostProcessor() {
				@Override public Object postProcessBeforeInitialization(Object bean, String name) {
					if (bean instanceof SimpleRabbitListenerContainerFactory factory) factory.setAutoStartup(false);
					return bean;
				}
			};
		}
	}

	@DynamicPropertySource
	static void isolatedConfiguration(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("PROCESSING_TEST_SCOPE_APPROVED"))) {
			throw new IllegalStateException("Explicit isolated HTTP test scope approval required");
		}
		for (String key : List.of("URL", "USERNAME", "PASSWORD")) {
			String value = required("PROCESSING_POSTGRES_TEST_" + key);
			registry.add("spring.datasource." + key.toLowerCase(Locale.ROOT), () -> value);
		}
		for (String key : List.of("HOST", "PORT", "DATABASE")) {
			String value = required("PROCESSING_REDIS_TEST_" + key);
			registry.add("spring.data.redis." + key.toLowerCase(Locale.ROOT), () -> value);
		}
		for (String key : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD")) {
			String value = required("PROCESSING_RABBITMQ_TEST_" + key);
			registry.add("spring.rabbitmq." + (key.equals("VHOST") ? "virtual-host" : key.toLowerCase(Locale.ROOT)), () -> value);
		}
		for (String key : List.of("URL", "USERNAME", "PASSWORD")) {
			String value = required("PROCESSING_CLICKHOUSE_TEST_" + key);
			registry.add("vanioak.clickhouse." + key.toLowerCase(Locale.ROOT), () -> value);
		}
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("vanioak.identity.bootstrap-admin.enabled", () -> "true");
		registry.add("vanioak.identity.bootstrap-admin.username", () -> ADMIN);
		registry.add("vanioak.identity.bootstrap-admin.password", () -> PASSWORD);
	}

	@LocalServerPort private int port;
	@Autowired private ObjectMapper json;
	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean private RabbitTemplate rabbit;
	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.h.vanioak.modules.identity.api.ApiKeyFacade credentials;
	@Autowired private com.h.vanioak.modules.ingestion.internal.IngestionService ingestion;
	@Autowired private RabbitAdmin admin;
	@Autowired private RabbitListenerEndpointRegistry listeners;
	@Autowired private ClickHouseConnection clickhouse;
	@Autowired private RawLogConsumer consumer;

	@Test
	@org.junit.jupiter.api.Timeout(120)
	void boundedApiKeyContextRevalidationUsesRealIdentityAndPipeline() throws Exception {
		long started = System.nanoTime();
		var evidence = java.nio.file.Path.of(required("PROCESSING_REVALIDATION_EVIDENCE_DIR"));
		String run = UUID.randomUUID().toString();
		var result = new java.util.LinkedHashMap<String, Object>();
		result.put("run_id", run);
		result.put("server_port", port);
		result.put("status", "STARTED");
		var originalClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils.getField(ingestion, "clock");
		var lookups = new java.util.concurrent.atomic.AtomicInteger();
		var confirms = new java.util.concurrent.CopyOnWriteArrayList<org.springframework.amqp.rabbit.connection.CorrelationData>();
		org.mockito.Mockito.doAnswer(call -> {
			lookups.incrementAndGet();
			return call.callRealMethod();
		}).when(credentials).verify(org.mockito.ArgumentMatchers.anyString());
		org.mockito.Mockito.doAnswer(call -> {
			confirms.add(call.getArgument(3));
			return call.callRealMethod();
		}).when(rabbit).send(org.mockito.ArgumentMatchers.eq("raw.exchange"), org.mockito.ArgumentMatchers.eq("raw.log"),
				org.mockito.ArgumentMatchers.any(org.springframework.amqp.core.Message.class),
				org.mockito.ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
		String access = null;
		String credentialId = null;
		boolean revoked = false;
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			assertEquals(Duration.ofSeconds(5), org.springframework.test.util.ReflectionTestUtils.getField(ingestion, "cacheTtl"));
			assertEquals(Duration.ofSeconds(30), org.springframework.test.util.ReflectionTestUtils.getField(credentials, "cacheTtl"));
			admin.initialize();
			for (String queue : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue")) {
				assertEquals(0, admin.getQueueInfo(queue).getMessageCount());
				assertEquals(0, admin.getQueueInfo(queue).getConsumerCount());
			}
			clickhouse.checkConnectivity();
			access = request(client, "/auth/login", Map.of("username", ADMIN, "password", PASSWORD), null, null, 200).get("access_token").asString();
			String name = "revalidation-" + run;
			var application = request(client, "/applications", Map.of("name", name), access, null, 201);
			String applicationId = application.get("id").asString();
			String environmentId = null;
			for (JsonNode environment : application.get("environments")) {
				if ("DEV".equals(environment.get("name").asString())) environmentId = environment.get("id").asString();
			}
			assertNotNull(environmentId);
			var key = request(client, "/applications/" + applicationId + "/environments/" + environmentId + "/api-keys", Map.of(), access, null, 201);
			credentialId = key.get("credential").get("id").asString();
			String secret = key.get("api_key").asString();
			result.put("application_id", applicationId);
			result.put("environment_id", environmentId);
			result.put("credential_id", credentialId);
			java.nio.file.Files.writeString(evidence.resolve("scope.json"), json.writeValueAsString(result), java.nio.file.StandardOpenOption.CREATE_NEW);
			var logs = IntStream.range(0, 5).mapToObj(i -> Map.<String, Object>of("application", name, "environment", "DEV",
					"host_ip", "192.0.2.10", "level", "INFO", "message", "Bounded API-key revalidation verification",
					"timestamp", Instant.now().toString(), "trace_id", run + "-" + i, "metadata", Map.of("verification", "revalidation"))).toList();
			listeners.start();
			request(client, "/logs", logs.get(0), null, secret, 202);
			assertConfirmed(confirms, 1);
			request(client, "/logs/batch", Map.of("logs", logs.subList(1, 3)), null, secret, 202);
			assertConfirmed(confirms, 2);
			assertEquals(1, lookups.get(), "The normal local-cache fast path must not repeat Identity lookup");
			result.put("fast_path", "PASS");
			var oldContext = ingestion.authenticate(secret);
			Instant deadline = oldContext.validUntil();
			assertTrue(deadline.isAfter(Instant.now()));
			assertTrue(deadline.isBefore(Instant.now().plusSeconds(31)), "The ordinary 30-second Identity cache budget must remain unchanged");
			// Prime only the test bean's local clock/cache near the real Identity deadline.
			org.springframework.test.util.ReflectionTestUtils.setField(ingestion, "clock", java.time.Clock.fixed(deadline.minusMillis(2), java.time.ZoneOffset.UTC));
			assertEquals(deadline, ingestion.authenticate(secret).validUntil());
			assertEquals(2, lookups.get());
			long waitMillis = Math.max(0, Duration.between(Instant.now(), deadline.plusMillis(150)).toMillis());
			assertTrue(waitMillis <= 31_000);
			Thread.sleep(waitMillis);
			assertTrue(Instant.now().isAfter(deadline));
			var boundaryClock = org.mockito.Mockito.mock(java.time.Clock.class);
			org.mockito.Mockito.when(boundaryClock.instant()).thenReturn(deadline.minusNanos(1), deadline);
			org.springframework.test.util.ReflectionTestUtils.setField(ingestion, "clock", boundaryClock);
			request(client, "/logs", logs.get(3), null, secret, 202);
			assertConfirmed(confirms, 3);
			assertEquals(3, lookups.get(), "Exactly one real Identity revalidation must follow the authenticated cache hit");
			result.put("boundary_authentication_instant", deadline.minusNanos(1).toString());
			result.put("boundary_ingestion_instant", deadline.toString());
			result.put("precise_boundary", "PASS: test-only clock; real Identity cache expiry and verification");
			org.springframework.test.util.ReflectionTestUtils.setField(ingestion, "clock", originalClock);
			request(client, "/logs", logs.get(4), null, secret, 202);
			assertConfirmed(confirms, 4);
			assertEquals(4, lookups.get(), "The naturally expired local context must resolve again through real Identity");
			assertTrue(ingestion.authenticate(secret).validUntil().isAfter(deadline));
			assertEquals(4, confirms.stream().map(org.springframework.amqp.rabbit.connection.CorrelationData::getId).distinct().count());
			result.put("native_positive_confirms", confirms.size());
			var wrong = new java.util.HashMap<>(logs.getFirst());
			wrong.put("application", name + "-wrong");
			request(client, "/logs", wrong, null, secret, 401);
			wrong.put("application", name);
			wrong.put("environment", "TEST");
			request(client, "/logs/batch", Map.of("logs", List.of(wrong)), null, secret, 401);
			request(client, "/logs", logs.getFirst(), null, "invalid-test-credential", 401);
			var revoke = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/api-keys/" + credentialId))
					.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).DELETE().build();
			var revokedResponse = client.send(revoke, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, revokedResponse.statusCode());
			assertEquals("REVOKED", json.readTree(revokedResponse.body()).get("data").get("status").asString());
			revoked = true;
			Thread.sleep(5_200); // Respect the existing local revocation/cache TTL; do not clear shared caches.
			request(client, "/logs", logs.getFirst(), null, secret, 401);
			assertEquals(4, confirms.size(), "Rejected requests must not invoke raw publication");
			result.put("rejected_without_publication", 4);
			var nativeFactory = new com.rabbitmq.client.ConnectionFactory();
			nativeFactory.setHost(required("PROCESSING_RABBITMQ_TEST_HOST"));
			nativeFactory.setPort(Integer.parseInt(required("PROCESSING_RABBITMQ_TEST_PORT")));
			nativeFactory.setVirtualHost(required("PROCESSING_RABBITMQ_TEST_VHOST"));
			nativeFactory.setUsername(required("PROCESSING_RABBITMQ_TEST_USERNAME"));
			nativeFactory.setPassword(required("PROCESSING_RABBITMQ_TEST_PASSWORD"));
			nativeFactory.setAutomaticRecoveryEnabled(false);
			nativeFactory.setConnectionTimeout(5000);
			nativeFactory.setChannelRpcTimeout(5000);
			var ids = new HashSet<String>();
			var traces = new HashSet<String>();
			try (var connection = nativeFactory.newConnection("revalidation-it-" + run); var channel = connection.createChannel();
					var journal = java.nio.channels.FileChannel.open(evidence.resolve("processed-events.ndjson"), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
				long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
				while (ids.size() < 5 && System.nanoTime() < drainDeadline) {
					var message = channel.basicGet("realtime.queue", false);
					if (message == null) { Thread.sleep(20); continue; }
					var event = json.readTree(message.getBody());
					var payload = event.get("payload");
					String id = event.get("event_id").asString();
					String trace = payload.get("trace_id").asString();
					assertEquals("processed.log", event.get("event_type").asString());
					assertEquals(id, payload.get("event_id").asString());
					assertEquals(id, message.getProps().getMessageId());
					assertEquals(applicationId, payload.get("application_id").asString());
					assertEquals(environmentId, payload.get("environment_id").asString());
					assertEquals("INFO", payload.get("level").asString());
					assertTrue(logs.stream().anyMatch(log -> trace.equals(log.get("trace_id"))), "Unexpected ownership; stop without ACK");
					assertTrue(ids.add(id), "Duplicate processed identity");
					assertTrue(traces.add(trace));
					byte[] record = (json.writeValueAsString(Map.of("event_id", id, "trace_id", trace, "application_id", applicationId,
							"environment_id", environmentId, "body_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(message.getBody())))) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
					var buffer = java.nio.ByteBuffer.wrap(record);
					while (buffer.hasRemaining()) journal.write(buffer);
					journal.force(true);
					channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
					channel.queueDeclarePassive("realtime.queue");
				}
			}
			assertEquals(5, ids.size());
			String sql = "SELECT * FROM logs WHERE application_id=toUUID('" + applicationId + "') AND environment_id=toUUID('" + environmentId
					+ "') AND startsWith(trace_id, '" + run + "-') SETTINGS max_threads=1,max_memory_usage=67108864,max_execution_time=5 FORMAT JSONEachRow";
			var query = HttpRequest.newBuilder(URI.create(required("PROCESSING_CLICKHOUSE_TEST_URL"))).timeout(Duration.ofSeconds(10))
					.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((required("PROCESSING_CLICKHOUSE_TEST_USERNAME") + ":"
							+ required("PROCESSING_CLICKHOUSE_TEST_PASSWORD")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
					.POST(HttpRequest.BodyPublishers.ofString(sql)).build();
			var rows = client.send(query, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, rows.statusCode());
			java.nio.file.Files.writeString(evidence.resolve("stored-rows.ndjson"), rows.body(), java.nio.file.StandardOpenOption.CREATE_NEW);
			var stored = new HashSet<String>();
			var storedTraces = new HashSet<String>();
			for (String row : rows.body().lines().toList()) {
				var value = json.readTree(row);
				assertEquals(11, value.size());
				assertTrue(stored.add(value.get("event_id").asString()));
				assertTrue(storedTraces.add(value.get("trace_id").asString()));
				assertEquals(applicationId, value.get("application_id").asString());
				assertEquals(environmentId, value.get("environment_id").asString());
				assertEquals("INFO", value.get("level").asString());
			}
			assertEquals(ids, stored);
			assertEquals(traces, storedTraces);
			assertEquals(logs.stream().map(log -> (String) log.get("trace_id")).collect(java.util.stream.Collectors.toSet()), storedTraces);
			long accountingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!accounted() && System.nanoTime() < accountingDeadline) Thread.sleep(20);
			assertTrue(accounted());
			listeners.stop();
			for (String queue : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue")) {
				assertEquals(0, admin.getQueueInfo(queue).getMessageCount());
				assertEquals(0, admin.getQueueInfo(queue).getConsumerCount());
			}
			result.put("accepted_requests", 4);
			result.put("accepted_logs", 5);
			result.put("stored_identities", stored.size());
			result.put("processed_identities", ids.size());
			result.put("duplicate_publications", 0);
			result.put("credential_revoked", true);
			result.put("status", "PASS");
		} finally {
			org.springframework.test.util.ReflectionTestUtils.setField(ingestion, "clock", originalClock);
			listeners.stop();
			if (!revoked && credentialId != null && access != null) {
				try (var client = HttpClient.newHttpClient()) {
					var revoke = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/api-keys/" + credentialId))
							.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).DELETE().build();
					result.put("cleanup_revoke_status", client.send(revoke, HttpResponse.BodyHandlers.discarding()).statusCode());
				}
			}
			result.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
			result.put("finished_at", Instant.now().toString());
			java.nio.file.Files.writeString(evidence.resolve("verification.json"), json.writeValueAsString(result), java.nio.file.StandardOpenOption.CREATE_NEW);
		}
	}

	private void assertConfirmed(List<org.springframework.amqp.rabbit.connection.CorrelationData> confirms, int expected) throws Exception {
		assertEquals(expected, confirms.size());
		for (var confirm : confirms) {
			assertTrue(confirm.getFuture().isDone(), "Native confirmation must already be available when HTTP 202 is observed");
			assertTrue(confirm.getFuture().get(2, TimeUnit.SECONDS).isAck());
			assertNull(confirm.getReturned());
		}
	}

	@Test
	void authenticatedSingleAndBatchIngestionPersistsAndPublishesEveryAcceptedTrace() throws Exception {
		admin.initialize();
		assertTrue(Boolean.TRUE.equals(rabbit.execute(channel -> channel.getConnection().getServerProperties()
				.get("version").toString().startsWith("4.2."))));
		for (String queue : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue")) {
			var info = admin.getQueueInfo(queue);
			assertNotNull(info);
			assertEquals(0, info.getMessageCount(), "Test queues must start empty");
			assertEquals(0, info.getConsumerCount(), "Test queues must be exclusive to this test scope");
		}
		clickhouse.checkConnectivity();
		String run = UUID.randomUUID().toString();
		String name = "processing-" + run;
		var expected = new java.util.HashMap<String, Map<String, Object>>();
		var ids = new HashSet<String>();
		String access = null;
		String credentialId = null;
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			access = request(client, "/auth/login", Map.of("username", ADMIN, "password", PASSWORD), null, null, 200)
					.get("access_token").asString();
			var application = request(client, "/applications", Map.of("name", name, "description", "Isolated processing IT"), access, null, 201);
			String applicationId = application.get("id").asString();
			String environmentId = null;
			for (JsonNode environment : application.get("environments")) {
				if ("DEV".equals(environment.get("name").asString())) environmentId = environment.get("id").asString();
			}
			assertNotNull(environmentId);
			String expectedEnvironmentId = environmentId;
			var key = request(client, "/applications/" + applicationId + "/environments/" + environmentId + "/api-keys",
					Map.of(), access, null, 201);
			credentialId = key.get("credential").get("id").asString();
			String secret = key.get("api_key").asString();
			listeners.start();
			for (int count : new int[] {1, 1, 100, 1000}) {
				int base = expected.size();
				var logs = IntStream.range(0, count).mapToObj(index -> Map.<String, Object>of(
						"application", name, "environment", "DEV", "host_ip", "192.0.2.10",
						"level", List.of("INFO", "WARN", "ERROR", "CRITICAL").get((base + index) % 4),
						"message", "Isolated \"quoted\"\nUTF-8: lỗi", "timestamp", Instant.now().toString(),
						"trace_id", run + "-" + (base + index), "metadata", Map.of("nested", List.of(1, true, "value")))).toList();
				logs.forEach(log -> expected.put((String) log.get("trace_id"), log));
				boolean single = base == 0;
				var accepted = request(client, single ? "/logs" : "/logs/batch", single ? logs.getFirst() : Map.of("logs", logs), null, secret, 202);
				assertTrue(accepted.get("accepted").asBoolean());
				if (!single) assertEquals(count, accepted.get("accepted_count").asInt());
			}
			request(client, "/logs", Map.of("level", "invalid"), null, secret, 400);
			var critical = new HashSet<String>();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
			while ((ids.size() < expected.size() || critical.size() < expected.values().stream()
					.filter(log -> List.of("ERROR", "CRITICAL").contains(log.get("level"))).count()) && System.nanoTime() < deadline) {
				for (String queue : List.of("realtime.queue", "alert.queue")) {
					rabbit.execute(channel -> {
						var message = channel.basicGet(queue, false);
						if (message == null) return null;
						try {
							var event = json.readTree(message.getBody());
							var payload = event.get("payload");
							var source = expected.get(payload.get("trace_id").asString());
							assertNotNull(source, "Unexpected message: requeue and stop");
							assertEquals(applicationId, payload.get("application_id").asString());
							assertEquals(expectedEnvironmentId, payload.get("environment_id").asString());
							assertEquals(queue.equals("realtime.queue") ? "processed.log" : "critical.log", event.get("event_type").asString());
							assertEquals(2, message.getProps().getDeliveryMode());
							assertEquals(source.get("message"), payload.get("message").asString());
							assertEquals(Instant.parse((String) source.get("timestamp")), Instant.parse(payload.get("timestamp").asString()));
							String id = event.get("event_id").asString();
							assertEquals(id, payload.get("event_id").asString());
							if (queue.equals("realtime.queue")) ids.add(id); else critical.add(id);
							channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
						} catch (Throwable failure) {
							channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
							throw failure;
						}
						return null;
					});
				}
				Thread.sleep(5);
			}
			assertEquals(expected.size(), ids.size());
			assertEquals(expected.values().stream().filter(log -> List.of("ERROR", "CRITICAL").contains(log.get("level"))).count(), critical.size());
			assertTrue(ids.containsAll(critical));
			// The observer checks real stored identities using the unchanged HTTP transport and contract table.
			var properties = new com.h.vanioak.common.config.clickhouse.ClickHouseProperties(
					URI.create(required("PROCESSING_CLICKHOUSE_TEST_URL")), required("PROCESSING_CLICKHOUSE_TEST_USERNAME"),
					required("PROCESSING_CLICKHOUSE_TEST_PASSWORD"), Duration.ofSeconds(5), Duration.ofSeconds(5));
			var query = HttpRequest.newBuilder(properties.url()).timeout(Duration.ofSeconds(10))
					.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
							(properties.username() + ":" + properties.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
					.POST(HttpRequest.BodyPublishers.ofString("SELECT event_id FROM logs WHERE startsWith(trace_id, '" + run + "-') GROUP BY event_id FORMAT JSONEachRow")).build();
			var rows = client.send(query, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, rows.statusCode());
			Set<String> stored = new HashSet<>();
			rows.body().lines().forEach(line -> stored.add(json.readTree(line).get("event_id").asString()));
			assertEquals(ids, stored);
			assertEquals(0, admin.getQueueInfo("dead-letter.queue").getMessageCount());
			long accountingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!accounted() && System.nanoTime() < accountingDeadline) Thread.sleep(10);
			assertTrue(accounted(), "Required publication accounting must finish before listener shutdown");
			listeners.stop();
			assertEquals(0, admin.getQueueInfo("raw.queue").getMessageCount());
		} finally {
			listeners.stop();
			if (credentialId != null && access != null) {
				try (var client = HttpClient.newHttpClient()) {
					var revoke = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/api-keys/" + credentialId))
						.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).DELETE().build();
					assertEquals(200, client.send(revoke, HttpResponse.BodyHandlers.discarding()).statusCode());
				}
			}
			// Leave relational fixtures and ClickHouse rows in the disposable scope; never delete shared state.
		}
	}

	private boolean accounted() {
		synchronized (org.springframework.test.util.ReflectionTestUtils.getField(consumer, "lifecycle")) {
			return ((Set<?>) org.springframework.test.util.ReflectionTestUtils.getField(consumer, "active")).isEmpty();
		}
	}

	private JsonNode request(HttpClient client, String path, Object body, String token, String key, int status) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
				.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		if (key != null) request.header("X-API-Key", key);
		var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(status, response.statusCode(), "HTTP status mismatch; bodies intentionally omitted");
		var envelope = json.readTree(response.body());
		assertEquals(status < 400, envelope.get("success").asBoolean());
		return envelope.get("data");
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalStateException("Explicit isolated test setting required: " + name);
		return value;
	}
}
