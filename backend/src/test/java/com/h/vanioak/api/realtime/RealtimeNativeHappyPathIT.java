package com.h.vanioak.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.BackendApplication;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeReadiness;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeLogConsumer;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application.properties", properties = {
		"server.address=127.0.0.1", "spring.rabbitmq.dynamic=false", "vanioak.processing.consumer-enabled=false",
		"logging.level.root=INFO", "logging.level.org.springframework=INFO", "logging.level.com.h.vanioak=INFO" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimeNativeHappyPathIT {
	static final Path FIXED_FENCE = Path.of("D:/Web System Development/vanioak/.local/realtime-native-happy-20261026/realtime.fence");
	static final String RUN = System.getenv("REALTIME_NATIVE_IT_RUN");
	static final String ADMIN = "rt-admin-" + RUN;
	static final String PASSWORD = UUID.randomUUID().toString();

	@DynamicPropertySource
	static void isolated(DynamicPropertyRegistry properties) {
		if (!"true".equals(System.getenv("REALTIME_NATIVE_IT_APPROVED"))
				|| !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(System.getenv("PROCESSING_POSTGRES_TEST_URL"))
				|| !"127.0.0.1".equals(System.getenv("PROCESSING_REDIS_TEST_HOST"))
				|| !"26379".equals(System.getenv("PROCESSING_REDIS_TEST_PORT"))
				|| !"0".equals(System.getenv("PROCESSING_REDIS_TEST_DATABASE"))
				|| !"http://127.0.0.1:28123".equals(System.getenv("PROCESSING_CLICKHOUSE_TEST_URL"))
				|| !"127.0.0.1".equals(System.getenv("PROCESSING_RABBITMQ_TEST_HOST"))
				|| !"25672".equals(System.getenv("PROCESSING_RABBITMQ_TEST_PORT"))
				|| !"processing-test".equals(System.getenv("PROCESSING_RABBITMQ_TEST_VHOST")))
			throw new IllegalStateException("Require approved isolated Realtime fixture");
		UUID.fromString(Objects.requireNonNull(RUN));
		Path directory = Path.of(Objects.requireNonNull(System.getenv("REALTIME_NATIVE_IT_EVIDENCE")));
		Path fence = Path.of(Objects.requireNonNull(System.getenv("REALTIME_NATIVE_IT_FENCE")));
		validateWiring(directory, fence, RUN, Instant.now(), p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS));
		for (String key : List.of("URL", "USERNAME", "PASSWORD")) {
			properties.add("spring.datasource." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_POSTGRES_TEST_" + key));
			properties.add("vanioak.clickhouse." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_CLICKHOUSE_TEST_" + key));
		}
		for (String key : List.of("HOST", "PORT", "DATABASE"))
			properties.add("spring.data.redis." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_REDIS_TEST_" + key));
		for (String key : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD"))
			properties.add("spring.rabbitmq." + (key.equals("VHOST") ? "virtual-host" : key.toLowerCase(Locale.ROOT)),
					() -> env("PROCESSING_RABBITMQ_TEST_" + key));
		properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		properties.add("vanioak.identity.bootstrap-admin.enabled", () -> "true");
		properties.add("vanioak.identity.bootstrap-admin.username", () -> ADMIN);
		properties.add("vanioak.identity.bootstrap-admin.password", () -> PASSWORD);
		properties.add("vanioak.realtime.enabled", () -> "true");
		properties.add("vanioak.realtime.fence-path", fence::toString);
	}

	static void validateWiring(Path directory, Path fence, String run, Instant now, Predicate<Path> exists) {
		try {
			UUID.fromString(run);
			if (!fence.isAbsolute() || !fence.equals(FIXED_FENCE) || exists.test(fence)
					|| !Files.isDirectory(fence.getParent(), LinkOption.NOFOLLOW_LINKS) || !fence.getParent().toRealPath().equals(fence.getParent())
					|| !directory.isAbsolute() || !directory.equals(directory.normalize())
					|| !directory.startsWith(FIXED_FENCE.getParent().getParent()) || directory.equals(FIXED_FENCE.getParent().getParent())
					|| directory.startsWith(FIXED_FENCE.getParent())
					|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
				throw new IllegalStateException("Invalid fixed fence or evidence directory");
			for (String name : List.of("REPORT.md", "scenario-state.json", "maven-exit.txt", "expected-inputs.json", "sse-0.txt", "sse-1.txt"))
				if (exists.test(directory.resolve(name))) throw new IllegalStateException("Stale evidence directory");
			ObjectMapper mapper = new ObjectMapper();
			JsonNode prepared = wiringRecord(mapper, directory.resolve("wiring-preparation.json"));
			JsonNode marker = wiringRecord(mapper, directory.resolve("one-shot.marker"));
			JsonNode preflight = wiringRecord(mapper, directory.resolve("preflight-before.json"));
			for (JsonNode record : List.of(prepared, marker)) {
				if (!run.equals(record.get("run_id").asString())
						|| !fence.equals(Path.of(record.get("fence_path").asString()))
						|| !directory.equals(Path.of(record.get("evidence_directory").asString())))
					throw new IllegalStateException("Unmatched invocation binding");
			}
			String nonce = prepared.get("nonce").asString(); UUID.fromString(nonce);
			if (!nonce.equals(marker.get("nonce").asString()) || !"PASS".equals(preflight.get("status").asString())
					|| !"RealtimeNativeHappyPathIT#oneExclusiveConsumerDeliversOnlyAuthorizedNormalEvents".equals(marker.get("selector").asString()))
				throw new IllegalStateException("Unverified one-shot invocation");
			for (JsonNode record : List.of(prepared, marker, preflight)) {
				String field = record == preflight ? "at_epoch" : "at";
				if (!record.get(field).isNumber()) throw new IllegalStateException("Invalid invocation time");
				double age = now.toEpochMilli() / 1000.0 - record.get(field).asDouble();
				if (!Double.isFinite(age) || age < -5 || age > (record == marker ? 210 : 330))
					throw new IllegalStateException("Stale invocation");
			}
		} catch (Exception failure) {
			throw new IllegalStateException("Require fresh verified evidence and the absent approved fixed fence");
		}
	}

	private static JsonNode wiringRecord(ObjectMapper mapper, Path path) throws java.io.IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096)
			throw new IllegalStateException("Missing or oversized invocation record");
		JsonNode node = mapper.readTree(Files.readAllBytes(path));
		if (!node.isObject()) throw new IllegalStateException("Invalid invocation record");
		return node;
	}

	static String env(String key) { return Objects.requireNonNull(System.getenv(key)); }
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired ConnectionFactory connection;
	@Autowired RealtimeReadiness readiness;
	@Autowired RealtimeLogConsumer consumer;
	@Autowired RealtimeService realtime;
	final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	final List<Map<String, String>> sessions = new ArrayList<>();
	final List<Probe> streams = new ArrayList<>();
	final List<Map<String, Object>> attempts = new ArrayList<>();
	final Map<String, Object> result = new LinkedHashMap<>();
	final List<String> users = new ArrayList<>();

	Path evidence() { return Path.of(env("REALTIME_NATIVE_IT_EVIDENCE")); }
	void save(String name, Object value) throws Exception {
		byte[] bytes = json.writeValueAsBytes(value);
		try (var channel = FileChannel.open(evidence().resolve(name), StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
		}
	}
	void phase(String value) throws Exception {
		result.put("run_id", RUN); result.put("port", port); result.put("phase", value); result.put("at", Instant.now().toString());
		result.put("user_ids", users); result.put("publication_attempts", attempts); save("scenario-state.json", result);
	}
	JsonNode api(String method, String path, String token, Object body, int status) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(5));
		if (token != null) request.header("Authorization", "Bearer " + token);
		if (body != null) request.header("Content-Type", "application/json");
		var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(status, response.statusCode());
		return json.readTree(response.body()).get("data");
	}
	String login(String name, String password) throws Exception {
		var data = api("POST", "/auth/login", null, Map.of("username", name, "password", password), 200);
		users.add(data.get("user").get("id").asString());
		var session = Map.of("access_token", data.get("access_token").asString(), "refresh_token", data.get("refresh_token").asString());
		sessions.add(session); return session.get("access_token");
	}
	JsonNode broker(String path) throws Exception {
		return broker(path, Duration.ofSeconds(3));
	}
	JsonNode broker(String path, Duration timeout) throws Exception {
		String basic = Base64.getEncoder().encodeToString((env("PROCESSING_RABBITMQ_TEST_USERNAME") + ":"
				+ env("PROCESSING_RABBITMQ_TEST_PASSWORD")).getBytes(StandardCharsets.UTF_8));
		var pending = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25673/api/" + path))
				.timeout(timeout).header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.ofString());
		try {
			var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
			assertEquals(200, response.statusCode()); return json.readTree(response.body());
		} catch (Exception | AssertionError failure) {
			pending.cancel(true); throw failure;
		}
	}

	record ExpectedRegistration(String tag, int channel, String clientConnection) { }
	record RegistrationView(JsonNode consumers, JsonNode connections) { }
	@FunctionalInterface interface RegistrationRead { RegistrationView read(long deadline) throws Exception; }
	@FunctionalInterface interface RegistrationSleep { void sleep(long nanos) throws Exception; }
	static JsonNode awaitRegistration(ExpectedRegistration expected, RegistrationRead read, BooleanSupplier nativeCurrent,
			LongSupplier clock, RegistrationSleep sleep, Consumer<Map<String, Object>> diagnostics) throws Exception {
		long start = clock.getAsLong(), deadline = start + TimeUnit.SECONDS.toNanos(10);
		int polls = 0, observedCount = -1;
		String failure = "MANAGEMENT_VISIBILITY_TIMEOUT";
		try {
			if (expected.tag() == null || expected.clientConnection() == null || expected.clientConnection().isBlank() || expected.channel() < 1)
				throw new AssertionError("Missing native registration identity");
			while (clock.getAsLong() < deadline) {
				if (!nativeCurrent.getAsBoolean()) { failure = "NATIVE_REGISTRATION_LOST"; break; }
				RegistrationView view;
				try { view = read.read(deadline); }
				catch (Exception queryFailure) {
					if (queryFailure instanceof InterruptedException) Thread.currentThread().interrupt();
					failure = "MANAGEMENT_QUERY_FAILED";
					throw new AssertionError("Management registration query failed: " + queryFailure.getClass().getSimpleName());
				}
				polls++;
				if (!nativeCurrent.getAsBoolean()) { failure = "NATIVE_REGISTRATION_LOST"; break; }
				if (clock.getAsLong() >= deadline) break;
				if (!view.consumers().isArray() || !view.connections().isArray()) throw new AssertionError("Invalid management response shape");
				observedCount = view.consumers().size();
				if (observedCount > 0) {
					failure = "WRONG_MANAGEMENT_REGISTRATION";
					assertEquals(1, observedCount);
					JsonNode registered = view.consumers().get(0), details = registered.get("channel_details");
					assertTrue(expected.tag().equals(registered.get("consumer_tag").asString()), "Unexpected consumer tag");
					assertEquals("realtime.queue", registered.get("queue").get("name").asString());
					assertEquals("processing-test", registered.get("queue").get("vhost").asString());
					assertEquals(expected.channel(), details.get("number").asInt());
					assertTrue(registered.get("exclusive").isBoolean() && registered.get("exclusive").asBoolean());
					assertTrue(registered.get("ack_required").isBoolean() && registered.get("ack_required").asBoolean());
					assertTrue(registered.get("active").isBoolean() && registered.get("active").asBoolean());
					assertEquals(1, registered.get("prefetch_count").asInt());
					String serverConnection = details.get("connection_name").asString();
					List<JsonNode> matching = new ArrayList<>();
					for (JsonNode connection : view.connections()) if (serverConnection.equals(connection.get("name").asString())) matching.add(connection);
					if (!matching.isEmpty()) {
						assertEquals(1, matching.size());
						assertTrue(expected.clientConnection().equals(matching.get(0).get("client_properties").get("connection_name").asString()), "Unexpected native connection identity");
						assertEquals("processing-test", matching.get(0).get("vhost").asString());
						if (!nativeCurrent.getAsBoolean()) { failure = "NATIVE_REGISTRATION_LOST"; break; }
						diagnostics.accept(Map.of("reason", "REGISTERED", "polls", polls, "elapsed_ms", TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start)));
						return view.consumers();
					}
				}
				failure = "MANAGEMENT_VISIBILITY_TIMEOUT";
				sleep.sleep(Math.max(0, Math.min(TimeUnit.MILLISECONDS.toNanos(100), deadline - clock.getAsLong())));
			}
			throw new AssertionError("Exact native consumer not visible within fixed registration deadline");
		} catch (Exception | AssertionError error) {
			if (error instanceof InterruptedException) Thread.currentThread().interrupt();
			diagnostics.accept(Map.of("reason", failure, "polls", polls, "last_consumer_count", observedCount,
					"native_current", nativeCurrent.getAsBoolean(), "expected_tag", Objects.toString(expected.tag(), "missing"), "expected_channel", expected.channel(),
					"elapsed_ms", TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start)));
			throw error;
		}
	}

	JsonNode registrationRequest(String path, long deadline) throws Exception {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Registration deadline");
		return broker(path, Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(1))));
	}
	String environment(JsonNode application) {
		for (var item : application.get("environments")) if ("DEV".equals(item.get("name").asString())) return item.get("id").asString();
		throw new AssertionError("Missing DEV scope");
	}
	ObjectNode event(String app, String environment, int sequence) {
		String time = Instant.now().toString(), id = UUID.randomUUID().toString();
		var root = json.createObjectNode();
		root.put("event_id", id).put("event_type", "processed.log").put("occurred_at", time).put("producer", "processing").put("schema_version", 1);
		var payload = root.putObject("payload");
		payload.put("event_id", id).put("application_id", app).put("environment_id", environment).put("host_ip", "127.0.0.1")
				.put("level", sequence < 2 ? "INFO" : "WARN").put("message", "Realtime native happy path " + RUN + "/" + sequence)
				.put("timestamp", time).put("trace_id", "realtime-" + RUN + "-" + sequence).put("received_at", time).put("processed_at", time);
		payload.putObject("metadata").put("verification_run", RUN).put("sequence", sequence).put("text", "Ti\u1ebfng Vi\u1ec7t \ud83d\ude42");
		return root;
	}

	@Test @Timeout(90)
	void oneExclusiveConsumerDeliversOnlyAuthorizedNormalEvents() throws Exception {
		phase("starting");
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
		while (!readiness.ready() && System.nanoTime() < until) Thread.sleep(100);
		assertTrue(readiness.ready(), "Exclusive registration did not establish readiness");
		Channel registeredChannel = (Channel) ReflectionTestUtils.getField(consumer, "sourceChannel"); assertNotNull(registeredChannel);
		var expectedRegistration = new ExpectedRegistration((String) ReflectionTestUtils.getField(consumer, "tag"),
				registeredChannel.getChannelNumber(), registeredChannel.getConnection().getClientProvidedName());
		result.put("registration_expected", Map.of("consumer_tag", expectedRegistration.tag(), "channel", expectedRegistration.channel()));
		phase("registration_wait");
		Map<String, Object> diagnostics = new LinkedHashMap<>();
		try {
			var visible = awaitRegistration(expectedRegistration, deadline -> {
				JsonNode consumers = registrationRequest("consumers/processing-test", deadline);
				return new RegistrationView(consumers, consumers.isArray() && consumers.isEmpty() ? json.createArrayNode() : registrationRequest("connections", deadline));
			}, () -> readiness.ready() && registeredChannel.isOpen() && registeredChannel == ReflectionTestUtils.getField(consumer, "sourceChannel"),
					System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), diagnostics::putAll);
			save("registration-management.json", visible);
		} finally {
			diagnostics.put("native_ready", readiness.ready()); diagnostics.put("native_channel_open", registeredChannel.isOpen());
			diagnostics.put("expected_tag", expectedRegistration.tag()); diagnostics.put("expected_channel", expectedRegistration.channel());
			save("registration-diagnostics.json", diagnostics);
		}
		phase("consumer_ready");
		String admin = login(ADMIN, PASSWORD);
		List<String> tokens = new ArrayList<>(); List<JsonNode> applications = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			String name = "rt-engineer-" + RUN + "-" + i, password = UUID.randomUUID().toString();
			var user = api("POST", "/users", admin, Map.of("username", name, "password", password), 201);
			var application = api("POST", "/applications", admin, Map.of("name", "rt-app-" + RUN + "-" + i), 201);
			api("POST", "/applications/" + application.get("id").asString() + "/engineers/" + user.get("id").asString(), admin, null, 200);
			applications.add(application); tokens.add(login(name, password));
		}
		List<java.util.concurrent.CompletableFuture<HttpResponse<InputStream>>> openings = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			var app = applications.get(i);
			URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/realtime/stream?application_id="
					+ app.get("id").asString() + "&environment_id=" + environment(app));
			openings.add(client.sendAsync(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + tokens.get(i))
					.header("Accept", "text/event-stream").timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofInputStream()));
		}
		for (int i = 0; i < 2; i++) {
			var response = openings.get(i).get(27, TimeUnit.SECONDS); assertEquals(200, response.statusCode());
			assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
			var stream = new Probe(response.body(), evidence().resolve("sse-" + i + ".txt"), json); streams.add(stream); stream.start();
		}
		assertEquals(2, ((List<?>) ReflectionTestUtils.getField(realtime, "owned")).size());
		phase("streams_open");
		var before = broker("queues/processing-test/realtime.queue"); save("queue-before-publish.json", before);
		var publisher = new RabbitTemplate(connection); publisher.setMandatory(true); publisher.setReturnsCallback(returned -> { });
		List<ObjectNode> expected = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			var app = applications.get(i % 2); var source = event(app.get("id").asString(), environment(app), i);
			byte[] bytes = json.writeValueAsBytes(source); var properties = new MessageProperties();
			properties.setContentType("application/json"); properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			properties.setMessageId(source.get("event_id").asString());
			var correlation = new CorrelationData(UUID.randomUUID().toString());
			var attempt = new LinkedHashMap<String, Object>();
			attempt.put("event_id", properties.getMessageId()); attempt.put("correlation", correlation.getId()); attempt.put("confirmed", false);
			attempt.put("body_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
			attempts.add(attempt); save("input-" + i + ".json", source); save("expected-inputs.json", expected); phase("publishing");
			publisher.send("processed.exchange", "processed.log", new Message(bytes, properties), correlation);
			var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
			attempt.put("confirmed", confirm.ack()); attempt.put("returned", correlation.getReturned() != null); phase("publishing");
			assertTrue(confirm.ack()); assertEquals(null, correlation.getReturned());
			var view = ((ObjectNode) source.get("payload")).deepCopy(); view.remove("received_at"); view.remove("processed_at");
			expected.add(view); save("expected-inputs.json", expected);
		}
		until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
		while ((streams.get(0).frames.size() < 2 || streams.get(1).frames.size() < 2) && System.nanoTime() < until) {
			for (var stream : streams) if (stream.failure.get() != null) throw new AssertionError("SSE reader failed", stream.failure.get());
			Thread.sleep(50);
		}
		Thread.sleep(2000);
		for (int i = 0; i < 2; i++) {
			assertEquals(null, streams.get(i).failure.get()); assertEquals(2, streams.get(i).frames.size());
			Set<String> seen = new HashSet<>(); String app = applications.get(i).get("id").asString();
			for (var frame : streams.get(i).frames) {
				assertEquals("log", frame.name()); assertEquals(app, frame.data().get("application_id").asString());
				assertTrue(seen.add(frame.data().get("event_id").asString()));
				assertTrue(expected.stream().anyMatch(frame.data()::equals)); assertEquals(9, frame.data().size());
			}
			assertEquals(Set.of(expected.get(i).get("event_id").asString(), expected.get(i + 2).get("event_id").asString()), seen);
		}
		until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); JsonNode after;
		do {
			after = broker("queues/processing-test/realtime.queue");
			if (after.path("messages_ready").asInt(-1) == 0 && after.path("messages_unacknowledged").asInt(-1) == 0) break;
			Thread.sleep(200);
		} while (System.nanoTime() < until);
		save("queue-after-publish.json", after);
		assertEquals(0, after.path("messages_ready").asInt(-1)); assertEquals(0, after.path("messages_unacknowledged").asInt(-1));
		var queues = broker("queues/processing-test"); save("queues-active-final.json", queues);
		assertEquals(5, queues.size());
		for (var q : queues) { assertEquals(0, q.path("messages_ready").asInt(-1)); assertEquals(0, q.path("messages_unacknowledged").asInt(-1)); }
		assertTrue(readiness.ready()); assertEquals(1, broker("consumers/processing-test").size());
		var threadInfo = java.lang.management.ManagementFactory.getThreadMXBean(); int writers = 0;
		for (var thread : threadInfo.getThreadInfo(threadInfo.getAllThreadIds())) if (thread != null && "realtime-writer".equals(thread.getThreadName())) writers++;
		assertTrue(writers <= 2); result.put("writer_threads_observed", writers); result.put("reconciled_events", 4);
		result.put("scope_ids", applications.stream().map(a -> Map.of("application_id", a.get("id").asString(), "environment_id", environment(a))).toList());
		result.put("outcome", "PASS"); phase("verified");
	}

	@AfterEach @Timeout(20)
	void cleanup() throws Exception {
		Exception failure = null;
		try {
			for (var stream : streams) { try { stream.close(); } catch (Exception ex) { if (failure == null) failure = ex; } }
			int revoked = 0;
			for (var session : sessions) {
				try {
					api("POST", "/auth/logout", session.get("access_token"), Map.of("refresh_token", session.get("refresh_token")), 200); revoked++;
				} catch (Exception | AssertionError ex) { if (failure == null) failure = new IllegalStateException("Session cleanup failed", ex); }
			}
			result.put("sessions_logged_out", revoked); phase(failure == null ? "cleanup_complete" : "cleanup_partial");
		} finally { client.shutdownNow(); }
		if (failure != null) throw failure;
	}

	record Frame(String name, JsonNode data) { }
	static Frame parse(String block, ObjectMapper mapper) {
		String name = null, data = null;
		for (String line : block.replace("\r\n", "\n").split("\n")) {
			if (line.isEmpty() || line.startsWith(":")) continue;
			if (line.startsWith("event:") && name == null) name = line.substring(6).stripLeading();
			else if (line.startsWith("data:") && data == null) data = line.substring(5).stripLeading();
			else throw new IllegalArgumentException("Unexpected SSE field");
		}
		if (name == null && data == null) return null;
		if (!"log".equals(name) || data == null) throw new IllegalArgumentException("Unexpected SSE frame");
		return new Frame(name, mapper.readTree(data));
	}
	static final class Probe implements AutoCloseable {
		final List<Frame> frames = new CopyOnWriteArrayList<>(); final AtomicReference<Throwable> failure = new AtomicReference<>();
		final InputStream body; final FileChannel journal; final ObjectMapper mapper; final Thread reader; volatile boolean closed;
		Probe(InputStream body, Path path, ObjectMapper mapper) throws Exception {
			this.body = body; this.mapper = mapper;
			journal = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			reader = new Thread(this::read, "realtime-it-sse-reader"); reader.setDaemon(true);
		}
		void start() { reader.start(); }
		void read() {
			try {
				var block = new ByteArrayOutputStream(); int previous = -1, suffix = 0, total = 0, b;
				while (!closed && (b = body.read()) >= 0) {
					if (++total > 65536 || block.size() >= 16384) throw new IllegalStateException("SSE evidence bound exceeded");
					block.write(b); suffix = (suffix << 8) | b;
					if ((b == 10 && previous == 10) || suffix == 0x0d0a0d0a) {
						byte[] raw = block.toByteArray(); ByteBuffer bytes = ByteBuffer.wrap(raw);
						while (bytes.hasRemaining()) journal.write(bytes); journal.force(true);
						String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
						Frame frame = parse(text, mapper); if (frame != null) { if (frames.size() >= 8) throw new IllegalStateException("Frame bound exceeded"); frames.add(frame); }
						block.reset();
					}
					previous = b;
				}
			} catch (Exception ex) { if (!closed) failure.set(ex); }
		}
		@Override public void close() throws Exception {
			closed = true;
			try { body.close(); } finally { try { reader.join(1500); } finally { journal.close(); } }
		}
	}
}
