package com.h.vanioak.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
import java.util.function.BiFunction;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.BackendApplication;
import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.modules.alert.internal.consumer.AlertConsumerLifecycle;
import com.h.vanioak.modules.alert.internal.consumer.AlertLogConsumer;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeLogConsumer;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application.properties", properties = {
		"server.address=127.0.0.1", "spring.rabbitmq.dynamic=false", "spring.flyway.enabled=false",
		"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate",
		"vanioak.processing.consumer-enabled=true", "vanioak.processing.consumer-prefetch=16",
		"vanioak.alert.consumer-enabled=true", "vanioak.realtime.enabled=true",
		"vanioak.alert.resolver-enabled=false", "logging.level.root=INFO"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendMvpSmokeIT {
	static final String SELECTOR = "BackendMvpSmokeIT#ingestedErrorsReachSearchIncidentsAndSse";
	static final Path ROOT = Path.of("D:/Web System Development/vanioak");
	static final Path ALERT_FENCE = ROOT.resolve(".local/alert-recovery.fence");
	static final Path RT_FENCE = ROOT.resolve(".local/realtime-native-happy-20261026/realtime.fence");
	static final Set<String> QUEUES = Set.of("raw.queue", "raw.retry.queue", "realtime.queue", "alert.queue", "alert.retry.queue", "dead-letter.queue");
	static final String RUN = System.getenv("B1_NATIVE_RUN");
	static final String ADMIN = "b1-admin-" + RUN;
	static final String PASSWORD = UUID.randomUUID().toString();

	static String env(String key) { return Objects.requireNonNull(System.getenv(key), "Missing runtime variable " + key); }

	static void requireApprovedScope(Map<String, String> values) {
		if (!"true".equals(values.get("B1_NATIVE_APPROVED"))
				|| !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(values.get("PROCESSING_POSTGRES_TEST_URL"))
				|| !"http://127.0.0.1:28123".equals(values.get("PROCESSING_CLICKHOUSE_TEST_URL"))
				|| !"127.0.0.1".equals(values.get("PROCESSING_REDIS_TEST_HOST"))
				|| !"26379".equals(values.get("PROCESSING_REDIS_TEST_PORT"))
				|| !"0".equals(values.get("PROCESSING_REDIS_TEST_DATABASE"))
				|| !"127.0.0.1".equals(values.get("PROCESSING_RABBITMQ_TEST_HOST"))
				|| !"25672".equals(values.get("PROCESSING_RABBITMQ_TEST_PORT"))
				|| !"processing-test".equals(values.get("PROCESSING_RABBITMQ_TEST_VHOST")))
			throw new IllegalStateException("Separate B1 native approval and exact isolated endpoints required");
		UUID.fromString(values.get("B1_NATIVE_RUN"));
	}

	@DynamicPropertySource
	static void isolated(DynamicPropertyRegistry properties) throws Exception {
		requireApprovedScope(System.getenv());
		Path directory = Path.of(env("B1_NATIVE_EVIDENCE")).toRealPath();
		if (Files.exists(ALERT_FENCE) || Files.exists(RT_FENCE) || !Files.isDirectory(ALERT_FENCE.getParent()))
			throw new IllegalStateException("Reviewed same-path recovery must complete before this single activation");
		var mapper = new ObjectMapper();
		for (String file : List.of("preflight.json", "one-shot.json", "recovery-result.json")) {
			var claim = mapper.readTree(Files.readAllBytes(directory.resolve(file)));
			if (!RUN.equals(claim.path("run_id").asString()) || !SELECTOR.equals(claim.path("selector").asString())
					|| !claim.path("pass").asBoolean())
				throw new IllegalStateException("Approved B1 preflight/recovery/one-shot binding required");
		}
		Files.writeString(directory.resolve("runtime-activation.claim"), RUN + "\n" + SELECTOR,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		for (String key : List.of("URL", "USERNAME", "PASSWORD")) {
			properties.add("spring.datasource." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_POSTGRES_TEST_" + key));
			properties.add("vanioak.clickhouse." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_CLICKHOUSE_TEST_" + key));
		}
		for (String key : List.of("HOST", "PORT", "DATABASE"))
			properties.add("spring.data.redis." + key.toLowerCase(Locale.ROOT), () -> env("PROCESSING_REDIS_TEST_" + key));
		for (String key : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD"))
			properties.add("spring.rabbitmq." + (key.equals("VHOST") ? "virtual-host" : key.toLowerCase(Locale.ROOT)),
					() -> env("PROCESSING_RABBITMQ_TEST_" + key));
		properties.add("vanioak.alert.fence-path", ALERT_FENCE::toString);
		properties.add("vanioak.realtime.fence-path", RT_FENCE::toString);
		properties.add("vanioak.identity.bootstrap-admin.enabled", () -> "true");
		properties.add("vanioak.identity.bootstrap-admin.username", () -> ADMIN);
		properties.add("vanioak.identity.bootstrap-admin.password", () -> PASSWORD);
	}

	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired EntityManager em;
	@Autowired ClickHouseConnection clickHouse;
	@Autowired StringRedisTemplate redis;
	@Autowired ConnectionFactory connection;
	@Autowired RabbitListenerEndpointRegistry listeners;
	@Autowired AlertConsumerLifecycle alert;
	@Autowired RealtimeLogConsumer realtimeConsumer;
	@Autowired Environment configuration;
	@Autowired ConfigurableApplicationContext context;
	@MockitoSpyBean RabbitTemplate rabbit;
	@MockitoSpyBean RealtimeService realtime;
	final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	final Map<String, Object> state = new LinkedHashMap<>();
	final Map<String, String> channels = new HashMap<>();
	final List<Session> sessions = new ArrayList<>();
	final List<Publication> publications = new CopyOnWriteArrayList<>();
	final List<Observation> observations = new CopyOnWriteArrayList<>();
	UUID application, environment;
	String adminToken, credentialId, apiKey;
	ProbeBridge stream;
	boolean normalStopVerified;
	record Session(String access, String refresh, UUID user) { }
	record Publication(String exchange, CorrelationData correlation) { }
	record Observation(RealtimeFacade.IncidentEvent event, RealtimeFacade.Fanout fanout) { }
	record Frame(String name, JsonNode data) { }
	@FunctionalInterface interface Check { boolean test() throws Exception; }

	@BeforeEach
	void observeRealCalls() {
		doAnswer(call -> {
			publications.add(new Publication(call.getArgument(0), call.getArgument(3)));
			return call.callRealMethod();
		}).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		doAnswer(call -> {
			var event = (RealtimeFacade.IncidentEvent) call.getArgument(0);
			var result = (RealtimeFacade.Fanout) call.callRealMethod();
			observations.add(new Observation(event, result));
			return result;
		}).when(realtime).publishIncident(any());
	}

	static void await(String label, int seconds, Check check) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < deadline) {
			if (check.test()) {
				assertTrue(System.nanoTime() < deadline, "Late convergence: " + label);
				return;
			}
			Thread.sleep(50);
		}
		fail("Deadline exceeded: " + label);
	}

	void save(String name, Object value) throws Exception {
		Files.write(Path.of(env("B1_NATIVE_EVIDENCE")).resolve(name), json.writeValueAsBytes(value));
	}

	void phase(String name) throws Exception {
		state.put("run_id", RUN); state.put("selector", SELECTOR); state.put("port", port); state.put("phase", name);
		if (application != null) state.put("application_id", application);
		if (environment != null) state.put("environment_id", environment);
		state.put("user_ids", sessions.stream().map(Session::user).toList());
		save("scenario-state.json", state);
	}

	JsonNode api(String method, String path, Object body, int status, String token) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(5));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		if (body != null) builder.header("Content-Type", "application/json");
		var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(status, response.statusCode(), method + " " + path);
		var envelope = json.readTree(response.body());
		assertTrue(envelope.has("success") && envelope.has("message") && envelope.has("data"));
		assertEquals(status < 400, envelope.get("success").asBoolean());
		return envelope.get("data");
	}

	String login(String username, String password) throws Exception {
		var data = api("POST", "/auth/login", Map.of("username", username, "password", password), 200, null);
		var session = new Session(data.get("access_token").asString(), data.get("refresh_token").asString(),
				UUID.fromString(data.get("user").get("id").asString()));
		sessions.add(session);
		return session.access();
	}

	JsonNode broker(String path) throws Exception {
		String auth = Base64.getEncoder().encodeToString((env("PROCESSING_RABBITMQ_TEST_USERNAME") + ":"
				+ env("PROCESSING_RABBITMQ_TEST_PASSWORD")).getBytes(StandardCharsets.UTF_8));
		var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25673/api/" + path))
				.timeout(Duration.ofSeconds(2)).header("Authorization", "Basic " + auth).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), "Broker observation unavailable");
		return json.readTree(response.body());
	}

	void registrations() throws Exception {
		await("three exact registrations", 10, () -> {
			if (!alert.ready() || !realtime.ready() || !listeners.getListenerContainer("processingRawConsumer").isRunning()) return false;
			var consumers = broker("consumers/processing-test");
			assertTrue(consumers.size() <= 3, "Unexpected consumers");
			if (consumers.size() != 3) return false;
			var known = Set.of("raw.queue", "realtime.queue", "alert.queue");
			Set<String> seen = new HashSet<>();
			String owner = connection.createConnection().getDelegate().getClientProvidedName();
			var connections = broker("connections");
			for (var consumer : consumers) {
				String queue = consumer.get("queue").get("name").asString();
				assertTrue(known.contains(queue) && seen.add(queue));
				assertEquals("processing-test", consumer.get("queue").get("vhost").asString());
				assertTrue(consumer.get("ack_required").asBoolean() && consumer.get("active").asBoolean());
				assertEquals(queue.equals("raw.queue") ? 16 : 1, consumer.get("prefetch_count").asInt());
				String connectionName = consumer.get("channel_details").get("connection_name").asString();
				JsonNode joined = null;
				for (var row : connections) if (connectionName.equals(row.get("name").asString())) joined = row;
				if (joined == null) return false;
				assertEquals(owner, joined.get("client_properties").get("connection_name").asString());
				if (!queue.equals("raw.queue")) {
					Object source = queue.equals("alert.queue") ? ReflectionTestUtils.getField(alert, "consumer") : realtimeConsumer;
					Channel nativeChannel = (Channel) ReflectionTestUtils.getField(source, queue.equals("alert.queue") ? "nativeSource" : "sourceChannel");
					assertNotNull(nativeChannel);
					assertTrue(nativeChannel.isOpen());
					assertEquals(nativeChannel.getChannelNumber(), consumer.get("channel_details").get("number").asInt());
					String tag = queue.equals("alert.queue") ? ((AlertLogConsumer) source).consumerTag()
							: (String) ReflectionTestUtils.getField(source, "tag");
					assertEquals(tag, consumer.get("consumer_tag").asString());
					if (queue.equals("realtime.queue")) assertTrue(consumer.get("exclusive").asBoolean());
				}
				channels.put(queue, consumer.get("channel_details").get("name").asString());
			}
			assertEquals(3, new HashSet<>(channels.values()).size());
			save("registrations.json", consumers);
			return true;
		});
	}

	ArrayNode rows(String sql) {
		var rows = json.createArrayNode();
		for (Object row : em.createNativeQuery(sql).setParameter("env", environment).getResultList()) rows.add(json.readTree(row.toString()));
		return rows;
	}

	Map<String, JsonNode> database() {
		return Map.of("receipts", rows("select row_to_json(t)::text from (select * from alert_event_receipts where environment_id=:env order by event_id)t"),
				"incidents", rows("select row_to_json(t)::text from (select * from incidents where environment_id=:env order by id)t"),
				"history", rows("select row_to_json(t)::text from (select h.* from incident_events h join incidents i on i.id=h.incident_id where i.environment_id=:env order by h.event_at,h.id)t"));
	}

	ArrayNode storedLogs() throws Exception {
		String sql = "SELECT * FROM logs FINAL WHERE application_id=toUUID('" + application + "') AND environment_id=toUUID('"
				+ environment + "') SETTINGS max_threads=1,max_execution_time=4,max_result_rows=4,result_overflow_mode='throw' FORMAT JSONEachRow";
		var result = json.createArrayNode();
		for (String line : clickHouse.queryReadOnly(sql).split("\n")) if (!line.isBlank()) result.add(json.readTree(line));
		return result;
	}

	void ackAccounting() throws Exception {
		await("native ACK accounting", 10, () -> {
			Set<String> seen = new HashSet<>();
			for (var channel : broker("channels")) {
				if (!channels.containsValue(channel.path("name").asString())) continue;
				var stats = channel.path("message_stats");
				if (!stats.has("ack") || !stats.has("deliver")) return false;
				assertTrue(stats.get("ack").asLong() <= 3 && stats.get("deliver").asLong() <= 3);
				if (stats.get("ack").asLong() != 3 || stats.get("deliver").asLong() != 3) return false;
				if (stats.has("redeliver")) assertEquals(0, stats.get("redeliver").asLong());
				seen.add(channel.get("name").asString());
			}
			if (seen.size() != 3) return false;
			save("ack-channels.json", broker("channels"));
			return true;
		});
	}

	void emptyQueues(boolean stopped) throws Exception {
		await("six idle queues", 10, () -> {
			var queues = broker("queues/processing-test");
			if (queues.size() != 6) return false;
			Set<String> names = new HashSet<>();
			for (var queue : queues) {
				names.add(queue.get("name").asString());
				if (!queue.has("messages_ready") || !queue.has("messages_unacknowledged") || !queue.has("consumers")) return false;
				if (queue.get("messages_ready").asLong() != 0 || queue.get("messages_unacknowledged").asLong() != 0) return false;
				if (stopped && queue.get("consumers").asInt() != 0) return false;
			}
			assertEquals(QUEUES, names);
			save(stopped ? "queues-after.json" : "queues-accounted.json", queues);
			return true;
		});
	}

	@Test
	@Timeout(120)
	void ingestedErrorsReachSearchIncidentsAndSse() throws Exception {
		assertFalse(configuration.getProperty("vanioak.alert.resolver-enabled", Boolean.class, true));
		assertFalse(configuration.getProperty("spring.rabbitmq.dynamic", Boolean.class, true));
		assertFalse(configuration.getProperty("spring.flyway.enabled", Boolean.class, true));
		registrations();
		phase("consumer_ready");
		save("queues-before.json", broker("queues/processing-test"));
		adminToken = login(ADMIN, PASSWORD);
		var app = api("POST", "/applications", Map.of("name", "b1-" + RUN), 201, adminToken);
		application = UUID.fromString(app.get("id").asString());
		assertEquals(3, app.get("environments").size());
		for (var value : app.get("environments")) if ("DEV".equals(value.get("name").asString())) environment = UUID.fromString(value.get("id").asString());
		assertNotNull(environment);
		phase("owned_application");
		var engineers = new ArrayList<String>();
		for (int i = 0; i < 2; i++) {
			String username = "b1-engineer-" + RUN + "-" + i, password = UUID.randomUUID().toString();
			var user = api("POST", "/users", Map.of("username", username, "password", password), 201, adminToken);
			if (i == 0) api("POST", "/applications/" + application + "/engineers/" + user.get("id").asString(), null, 200, adminToken);
			engineers.add(login(username, password));
		}
		var credential = api("POST", "/applications/" + application + "/environments/" + environment + "/api-keys", Map.of(), 201, adminToken);
		credentialId = credential.get("credential").get("id").asString();
		apiKey = credential.get("api_key").asString();
		var rule = api("POST", "/alert-rules", Map.of("environment_id", environment, "level", "ERROR", "threshold", 2,
				"window_seconds", 300, "cooldown_seconds", 60, "resolve_after_seconds", 3600, "enabled", true), 201, adminToken);
		state.put("credential_id", credentialId); state.put("rule_id", rule.get("id").asString()); phase("owned_fixtures");
		String scope = "application_id=" + application + "&environment_id=" + environment;
		api("GET", "/logs?" + scope, null, 403, engineers.get(1));
		api("GET", "/realtime/stream?" + scope, null, 403, engineers.get(1));
		// An idle stream may commit its HTTP response on the normal 15-second heartbeat.
		var response = http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/realtime/stream?" + scope + "&level=ERROR"))
				.header("Authorization", "Bearer " + engineers.getFirst()).header("Accept", "text/event-stream")
				.GET().build(), HttpResponse.BodyHandlers.ofInputStream()).get(25, TimeUnit.SECONDS);
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
		stream = new ProbeBridge(response.body(), Path.of(env("B1_NATIVE_EVIDENCE")).resolve("sse.txt"), json);
		stream.start();
		phase("stream_admitted");
		String message = "B1 stable ERROR " + RUN + " café Ω";
		Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Set<String> traces = new HashSet<>();
		for (int i = 0; i < 3; i++) {
			String trace = RUN + "-" + i;
			traces.add(trace);
			var body = Map.of("application", "b1-" + RUN, "environment", "DEV", "host_ip", "10.0.0.1", "level", "ERROR",
					"message", message, "timestamp", base.plusMillis(i).toString(), "trace_id", trace, "metadata", Map.of("run", RUN));
			save("input-" + i + ".json", body); state.put("source_posts_attempted", i + 1); phase("ingesting");
			var accepted = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/logs"))
					.timeout(Duration.ofSeconds(6)).header("X-API-Key", apiKey).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(202, accepted.statusCode());
			var envelope = json.readTree(accepted.body());
			assertTrue(envelope.get("success").asBoolean());
			assertTrue(envelope.get("data").get("accepted").asBoolean());
			state.put("request_" + i, envelope.get("data").get("request_id").asString());
			phase("ingest_accepted");
		}
		await("committed state", 10, () -> storedLogs().size() == 3 && database().get("receipts").size() == 3
				&& database().get("history").size() == 2);
		var db = database();
		var stored = storedLogs();
		save("postgres-final.json", db);
		save("clickhouse-final.json", stored);
		assertEquals(1, db.get("incidents").size());
		var incident = db.get("incidents").get(0);
		assertEquals("OPEN", incident.get("status").asString());
		assertEquals(3, incident.get("error_count").asInt());
		Set<String> eventIds = new HashSet<>(), actualTraces = new HashSet<>(), receiptIds = new HashSet<>();
		for (var row : stored) { eventIds.add(row.get("event_id").asString()); actualTraces.add(row.get("trace_id").asString()); }
		assertEquals(3, eventIds.size());
		assertEquals(traces, actualTraces);
		for (var receipt : db.get("receipts")) { receiptIds.add(receipt.get("event_id").asString()); assertEquals(incident.get("id"), receipt.get("incident_id")); }
		assertEquals(eventIds, receiptIds);
		assertEquals("OPENED", db.get("history").get(0).get("event_type").asString());
		assertEquals(2, db.get("history").get(0).get("error_count").asInt());
		assertEquals("COUNT_UPDATED", db.get("history").get(1).get("event_type").asString());
		assertEquals(3, db.get("history").get(1).get("error_count").asInt());
		String frequency = "vanioak:alert:frequency:" + environment + ":" + incident.get("fingerprint").asString() + ":" + rule.get("id").asString();
		assertEquals(eventIds, redis.opsForZSet().range(frequency, 0, -1));
		assertTrue(redis.getExpire(frequency) > 0);
		String assigned = engineers.getFirst();
		String time = "&from=" + base.minusSeconds(1) + "&to=" + base.plusSeconds(2);
		api("GET", "/analytics/health?" + scope + time + "&bucket=hour", null, 403, engineers.get(1));
		var first = api("GET", "/logs?" + scope + time + "&limit=2", null, 200, assigned);
		assertEquals(2, first.get("items").size());
		String cursor = first.get("next_cursor").asString();
		assertFalse(cursor.isBlank());
		var second = api("GET", "/logs?" + scope + time + "&limit=2&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8), null, 200, assigned);
		assertEquals(1, second.get("items").size());
		assertTrue(second.get("next_cursor").isNull());
		Set<String> paged = new HashSet<>();
		for (var page : List.of(first, second)) for (var item : page.get("items")) assertTrue(paged.add(item.get("event_id").asString()));
		assertEquals(eventIds, paged);
		await("analytics visibility", 10, () -> {
			var analytics = api("GET", "/analytics/health?" + scope + time + "&bucket=hour", null, 200, assigned);
			long total = 0, errors = 0, critical = 0;
			for (var item : analytics.get("items")) {
				assertEquals(application.toString(), item.get("application_id").asString());
				assertEquals(environment.toString(), item.get("environment_id").asString());
				total += item.get("total_logs").asLong(); errors += item.get("error_logs").asLong(); critical += item.get("critical_logs").asLong();
				assertEquals(1.0, item.get("log_error_rate").asDouble());
			}
			if (total != 3) return false;
			assertEquals(3, errors); assertEquals(0, critical); save("analytics.json", analytics); return true;
		});
		var list = api("GET", "/incidents?" + scope, null, 200, assigned);
		assertEquals(1, list.get("items").size());
		assertFalse(list.get("items").get(0).has("events"));
		assertEquals(incident.get("id"), list.get("items").get(0).get("id"));
		var detail = api("GET", "/incidents/" + incident.get("id").asString(), null, 200, assigned);
		assertEquals(3, detail.get("error_count").asInt());
		assertEquals("OPEN", detail.get("status").asString());
		assertEquals(application.toString(), detail.get("application").get("id").asString());
		assertEquals(environment.toString(), detail.get("environment").get("id").asString());
		assertEquals(2, detail.get("events").size());
		api("GET", "/incidents/" + incident.get("id").asString(), null, 403, engineers.get(1));
		await("five SSE frames", 10, () -> stream.snapshot(false).size() == 5 && observations.size() == 2);
		Thread.sleep(3000);
		assertFrames(stream.snapshot(false), stored, incident, db.get("history"), message);
		assertEquals(2, observations.size());
		for (var observation : observations) {
			var event = observation.event();
			assertEquals(application, event.applicationId()); assertEquals(environment, event.environmentId());
			assertEquals("alert", event.producer()); assertEquals(1, event.schemaVersion());
			assertTrue(observation.fanout().accounted());
			boolean found = false;
			for (var history : db.get("history")) if (history.get("id").asString().equals(event.eventId().toString())) {
				assertEquals(Instant.parse(history.get("event_at").asString()), event.occurredAt()); found = true;
			}
			assertTrue(found);
		}
		save("facade-observations.json", observations);
		await("nine native confirmations", 10, () -> publications.size() == 9);
		for (String exchange : List.of("raw.exchange", "processed.exchange", "critical.exchange"))
			assertEquals(3, publications.stream().filter(value -> exchange.equals(value.exchange())).count());
		for (var publication : publications) {
			assertTrue(publication.correlation().getFuture().get(5, TimeUnit.SECONDS).ack());
			assertNull(publication.correlation().getReturned());
		}
		ackAccounting(); emptyQueues(false);
		stream.beginPlannedStop(); realtimeConsumer.stop();
		await("planned Realtime retirement", 5, () -> !realtime.ready() && !stream.alive()
				&& java.util.stream.StreamSupport.stream(broker("consumers/processing-test").spliterator(), false)
						.noneMatch(c -> "realtime.queue".equals(c.get("queue").get("name").asString())));
		normalStopVerified = true;
		assertFrames(stream.snapshot(true), stored, incident, db.get("history"), message);
		state.put("outcome", "PASS"); state.put("application_id", application); state.put("environment_id", environment);
		state.put("incident_id", incident.get("id")); state.put("event_ids", eventIds);
		save("scenario-state.json", state);
	}

	void assertFrames(List<Frame> frames, JsonNode logs, JsonNode incident, JsonNode history, String message) {
		assertEquals(5, frames.size());
		Map<String, JsonNode> expected = new HashMap<>();
		for (var row : logs) expected.put(row.get("event_id").asString(), row);
		Set<String> logIds = new HashSet<>(), types = new HashSet<>();
		for (var frame : frames) {
			var data = frame.data();
			if (frame.name().equals("log")) {
				assertEquals(9, data.size());
				assertEquals(Set.of("event_id", "application_id", "environment_id", "host_ip", "level", "message", "timestamp", "trace_id", "metadata"),
						data.propertyNames());
				assertEquals(application.toString(), data.get("application_id").asString());
				assertEquals(environment.toString(), data.get("environment_id").asString());
				assertEquals("ERROR", data.get("level").asString()); assertEquals(message, data.get("message").asString());
				String id = data.get("event_id").asString(); assertTrue(logIds.add(id));
				assertTrue(expected.containsKey(id)); assertEquals(expected.get(id).get("trace_id"), data.get("trace_id"));
				assertEquals(expected.get(id).get("host_ip"), data.get("host_ip"));
				String storedTime = expected.get(id).get("timestamp").asString();
				assertEquals(Instant.parse(storedTime.endsWith("Z") ? storedTime : storedTime.replace(' ', 'T') + "Z"),
						Instant.parse(data.get("timestamp").asString()));
				assertEquals(json.readTree(expected.get(id).get("metadata_json").asString()), data.get("metadata"));
			} else {
				assertTrue(types.add(frame.name())); assertEquals(incident.get("id"), data.get("incident_id"));
				if (frame.name().equals("incident.created")) {
					assertEquals(7, data.size()); assertEquals(2, data.get("error_count").asInt());
					assertEquals(application.toString(), data.get("application_id").asString());
					assertEquals(environment.toString(), data.get("environment_id").asString());
					assertEquals("OPEN", data.get("status").asString()); assertEquals("ERROR", data.get("level").asString());
					assertEquals(message, data.get("message").asString());
				} else {
					assertEquals("incident.updated", frame.name()); assertEquals(3, data.size());
					assertEquals(3, data.get("error_count").asInt());
					assertEquals(Instant.parse(incident.get("last_seen_at").asString()), Instant.parse(data.get("last_seen_at").asString()));
				}
			}
		}
		assertEquals(expected.keySet(), logIds);
		assertEquals(Set.of("incident.created", "incident.updated"), types);
		assertEquals(2, history.size());
	}

	@AfterEach
	@Timeout(20)
	void cleanup() throws Exception {
		Throwable cleanupFailure = null;
		try {
			try { if (credentialId != null) api("DELETE", "/api-keys/" + credentialId, null, 200, adminToken); }
			catch (Exception | AssertionError error) { cleanupFailure = error; }
			int loggedOut = 0;
			for (var session : sessions) {
				try {
					api("POST", "/auth/logout", Map.of("refresh_token", session.refresh()), 200, session.access());
					Number pending = (Number) em.createNativeQuery("select count(*) from refresh_tokens where user_id=:user and revoked=false")
							.setParameter("user", session.user()).getSingleResult();
					assertEquals(0, pending.longValue()); loggedOut++;
				} catch (Exception | AssertionError error) { cleanupFailure = error; }
			}
			state.put("sessions_logged_out", loggedOut);
			listeners.stop(); realtimeConsumer.close(); alert.close();
			if (stream != null) stream.close();
			assertTrue(alert.shutdownSettled()); emptyQueues(true);
			assertEquals(0, broker("consumers/processing-test").size());
			assertArrayEquals("ALERT_ARMED_REVIEW_REQUIRED\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(ALERT_FENCE));
			assertArrayEquals("REALTIME_ARMED_REVIEW_REQUIRED\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(RT_FENCE));
			context.close();
			await("owned AMQP retirement", 5, () -> broker("connections").isEmpty() && broker("channels").isEmpty());
			try (var pg = DriverManager.getConnection(env("PROCESSING_POSTGRES_TEST_URL"), env("PROCESSING_POSTGRES_TEST_USERNAME"), env("PROCESSING_POSTGRES_TEST_PASSWORD"))) {
				pg.setReadOnly(true);
				try (var statement = pg.createStatement()) {
					statement.setQueryTimeout(2);
					try (var result = statement.executeQuery("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'")) {
						assertTrue(result.next()); assertEquals(0, result.getLong(1));
					}
				}
			}
			state.put("cleanup_complete", cleanupFailure == null); state.put("normal_stream_stop_verified", normalStopVerified);
			state.put("external_postflight_required", "Exact process/socket/fixture/history/new-marker identity reconciliation by approved launcher");
			save("scenario-state.json", state);
			if (cleanupFailure != null) throw new AssertionError("Owned key/session cleanup failed", cleanupFailure);
		} finally {
			if (context.isActive()) context.close();
			http.shutdownNow();
		}
	}

	static Frame parse(String block, ObjectMapper mapper) {
		String name = null, data = null;
		for (String line : block.replace("\r\n", "\n").split("\n")) {
			if (line.isBlank() || line.startsWith(":")) continue;
			if (line.startsWith("event:") && name == null) name = line.substring(6).stripLeading();
			else if (line.startsWith("data:") && data == null) data = line.substring(5).stripLeading();
			else throw new IllegalArgumentException("Unexpected SSE field");
		}
		if (name == null && data == null) return null;
		if (!Set.of("log", "incident.created", "incident.updated").contains(name) || data == null)
			throw new IllegalArgumentException("Unexpected SSE frame");
		var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(data);
		if (!node.isObject()) throw new IllegalArgumentException("Expected SSE object");
		return new Frame(name, node);
	}

	// Reuse the unchanged package-private reader's phase/journal checks from the approved native harness.
	static final class ProbeBridge implements AutoCloseable {
		private final Object probe;
		ProbeBridge(InputStream body, Path path, ObjectMapper mapper) throws Exception {
			Class<?> frameType = Class.forName("com.h.vanioak.api.alert.AlertIncidentSseNativeIT$Frame");
			Constructor<?> frame = frameType.getDeclaredConstructor(String.class, JsonNode.class); frame.setAccessible(true);
			BiFunction<String, ObjectMapper, Object> parser = (block, json) -> {
				Frame parsed = parse(block, json);
				if (parsed == null) return null;
				try { return frame.newInstance(parsed.name(), parsed.data()); }
				catch (ReflectiveOperationException ex) { throw new IllegalStateException("SSE helper binding failed", ex); }
			};
			var constructor = Class.forName("com.h.vanioak.api.alert.AlertIncidentSseNativeIT$Probe")
					.getDeclaredConstructor(InputStream.class, Path.class, ObjectMapper.class, BiFunction.class);
			constructor.setAccessible(true); probe = constructor.newInstance(body, path, mapper, parser);
		}
		void start() throws Exception { invoke("start", new Class<?>[0]); }
		void beginPlannedStop() throws Exception { invoke("beginPlannedStop", new Class<?>[0]); }
		boolean alive() { return ((Thread) Objects.requireNonNull(ReflectionTestUtils.getField(probe, "reader"))).isAlive(); }
		List<Frame> snapshot(boolean normalStop) throws Exception {
			var result = new ArrayList<Frame>();
			for (Object value : (List<?>) invoke("capturedFrames", new Class<?>[] {boolean.class}, normalStop)) {
				var name = value.getClass().getDeclaredMethod("name"); name.setAccessible(true);
				var data = value.getClass().getDeclaredMethod("data"); data.setAccessible(true);
				result.add(new Frame((String) name.invoke(value), (JsonNode) data.invoke(value)));
			}
			return List.copyOf(result);
		}
		private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
			var method = probe.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
			try { return method.invoke(probe, arguments); }
			catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof Exception exception) throw exception;
				if (ex.getCause() instanceof Error error) throw error;
				throw new IllegalStateException("SSE helper invocation failed");
			}
		}
		@Override public void close() throws Exception { invoke("close", new Class<?>[0]); }
	}

	public static class OfflineChecks {
		@TempDir Path directory;
		@Test void nativeGateRejectsUnapprovedScopeBeforeAnyAccess() {
			assertThrows(IllegalStateException.class, () -> requireApprovedScope(Map.of()));
		}
		@Test void parserRejectsUnexpectedFieldsAndEvents() {
			var json = new ObjectMapper();
			assertNull(parse(": heartbeat\n\n", json));
			assertEquals("log", parse("event:log\ndata:{}\n\n", json).name());
			assertThrows(IllegalArgumentException.class, () -> parse("id:1\nevent:log\ndata:{}\n\n", json));
			assertThrows(IllegalArgumentException.class, () -> parse("event:incident.resolved\ndata:{}\n\n", json));
			assertThrows(RuntimeException.class, () -> parse("event:log\ndata:{} {}\n\n", json));
		}
		@Test void bridgePreservesStrictPlannedStopJournalChecks() throws Exception {
			var input = new PipedInputStream();
			try (var output = new PipedOutputStream(input); var probe = new ProbeBridge(input, directory.resolve("stream.txt"), new ObjectMapper())) {
				probe.start(); output.write("event:log\ndata:{}\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
				await("offline frame", 5, () -> probe.snapshot(false).size() == 1);
				var before = probe.snapshot(false); probe.beginPlannedStop(); output.close();
				await("offline reader retirement", 5, () -> !probe.alive());
				assertEquals(before, probe.snapshot(true));
			}
		}
		@Test void bridgeStillRejectsEarlyEof() throws Exception {
			try (var probe = new ProbeBridge(new ByteArrayInputStream(new byte[0]), directory.resolve("early.txt"), new ObjectMapper())) {
				probe.start(); await("early reader EOF", 5, () -> !probe.alive());
				assertThrows(AssertionError.class, () -> probe.snapshot(false));
				assertThrows(AssertionError.class, probe::beginPlannedStop);
			}
		}
		@Test void functionalChecksRejectMissingAdditionalAndForeignFrames() {
			var test = new BackendMvpSmokeIT();
			try {
				test.json = new ObjectMapper(); test.application = UUID.randomUUID(); test.environment = UUID.randomUUID();
				var logs = test.json.createArrayNode(); var frames = new ArrayList<Frame>();
				String message = "offline";
				for (int i = 0; i < 3; i++) {
					String id = UUID.randomUUID().toString();
					var row = test.json.createObjectNode().put("event_id", id).put("trace_id", "trace-" + i)
							.put("host_ip", "10.0.0.1").put("timestamp", "2026-10-28 00:00:00.000").put("metadata_json", "{}");
					logs.add(row);
					var data = test.json.createObjectNode().put("event_id", id).put("application_id", test.application.toString())
							.put("environment_id", test.environment.toString()).put("host_ip", "10.0.0.1").put("level", "ERROR")
							.put("message", message).put("timestamp", "2026-10-28T00:00:00Z").put("trace_id", "trace-" + i);
					data.putObject("metadata"); frames.add(new Frame("log", data));
				}
				String id = UUID.randomUUID().toString();
				var incident = test.json.createObjectNode().put("id", id).put("last_seen_at", "2026-10-28T00:00:00Z");
				var created = test.json.createObjectNode().put("incident_id", id).put("application_id", test.application.toString())
						.put("environment_id", test.environment.toString()).put("level", "ERROR").put("status", "OPEN")
						.put("message", message).put("error_count", 2);
				var updated = test.json.createObjectNode().put("incident_id", id).put("error_count", 3).put("last_seen_at", "2026-10-28T00:00:00Z");
				frames.add(1, new Frame("incident.created", created)); frames.add(new Frame("incident.updated", updated));
				var twoHistories = test.json.createArrayNode();
				twoHistories.addObject(); twoHistories.addObject();
				test.assertFrames(frames, logs, incident, twoHistories, message);
				assertThrows(AssertionError.class, () -> test.assertFrames(frames.subList(0, 4), logs, incident, twoHistories, message));
				var extra = new ArrayList<>(frames); extra.add(frames.getFirst());
				assertThrows(AssertionError.class, () -> test.assertFrames(extra, logs, incident, twoHistories, message));
				created.put("application_id", UUID.randomUUID().toString());
				assertThrows(AssertionError.class, () -> test.assertFrames(frames, logs, incident, twoHistories, message));
			} finally { test.http.shutdownNow(); }
		}
	}
}
