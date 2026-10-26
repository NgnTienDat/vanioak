package com.h.vanioak.api.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import com.h.vanioak.BackendApplication;
import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application.properties", properties = { "server.address=127.0.0.1",
		"spring.rabbitmq.dynamic=false", "vanioak.processing.consumer-enabled=false" })
class AnalysisAccessIT {
	static final String ADMIN = "analysis-admin-" + UUID.randomUUID(), PASSWORD = UUID.randomUUID().toString();

	@DynamicPropertySource
	static void isolated(DynamicPropertyRegistry p) {
		if (!"true".equals(System.getenv("ANALYSIS_IT_APPROVED"))
				|| !"jdbc:postgresql://127.0.0.1:25432/processing_test"
						.equals(System.getenv("PROCESSING_POSTGRES_TEST_URL"))
				|| !"127.0.0.1".equals(System.getenv("PROCESSING_REDIS_TEST_HOST"))
				|| !"26379".equals(System.getenv("PROCESSING_REDIS_TEST_PORT"))
				|| !"0".equals(System.getenv("PROCESSING_REDIS_TEST_DATABASE"))
				|| !"http://127.0.0.1:28123".equals(System.getenv("PROCESSING_CLICKHOUSE_TEST_URL"))
				|| !"127.0.0.1".equals(System.getenv("PROCESSING_RABBITMQ_TEST_HOST"))
				|| !"25672".equals(System.getenv("PROCESSING_RABBITMQ_TEST_PORT"))
				|| !"processing-test".equals(System.getenv("PROCESSING_RABBITMQ_TEST_VHOST")))
			throw new IllegalStateException("Require separately approved isolated Analysis fixture");
		for (String k : List.of("URL", "USERNAME", "PASSWORD")) {
			p.add("spring.datasource." + k.toLowerCase(Locale.ROOT),
					() -> Objects.requireNonNull(System.getenv("PROCESSING_POSTGRES_TEST_" + k)));
			p.add("vanioak.clickhouse." + k.toLowerCase(Locale.ROOT),
					() -> Objects.requireNonNull(System.getenv("PROCESSING_CLICKHOUSE_TEST_" + k)));
		}
		for (String k : List.of("HOST", "PORT", "DATABASE"))
			p.add("spring.data.redis." + k.toLowerCase(Locale.ROOT),
					() -> Objects.requireNonNull(System.getenv("PROCESSING_REDIS_TEST_" + k)));
		for (String k : List.of("HOST", "PORT", "VHOST", "USERNAME", "PASSWORD"))
			p.add("spring.rabbitmq." + (k.equals("VHOST") ? "virtual-host" : k.toLowerCase(Locale.ROOT)),
					() -> Objects.requireNonNull(System.getenv("PROCESSING_RABBITMQ_TEST_" + k)));
		p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		p.add("vanioak.identity.bootstrap-admin.enabled", () -> "true");
		p.add("vanioak.identity.bootstrap-admin.username", () -> ADMIN);
		p.add("vanioak.identity.bootstrap-admin.password", () -> PASSWORD);
	}

	@LocalServerPort
	int port;
	@Autowired
	ObjectMapper json;
	@Autowired
	ClickHouseConnection writer;
	final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	final List<Map<String, String>> sessions = new ArrayList<>();

	private JsonNode call(String method, String path, String token, Object body, int status) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
				.timeout(Duration.ofSeconds(8));
		if (token != null)
			request.header("Authorization", "Bearer " + token);
		if (body != null)
			request.header("Content-Type", "application/json");
		var response = client.send(
				request.method(method,
						body == null ? HttpRequest.BodyPublishers.noBody()
								: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(status, response.statusCode());
		return json.readTree(response.body()).get("data");
	}

	private String login(String name, String password) throws Exception {
		var d = call("POST", "/auth/login", null, Map.of("username", name, "password", password), 200);
		var s = Map.of("access_token", d.get("access_token").asString(), "refresh_token",
				d.get("refresh_token").asString());
		sessions.add(s);
		return s.get("access_token");
	}

	@AfterEach
	@Timeout(30)
	void logout() throws Exception {
		try {
			for (var s : sessions)
				call("POST", "/auth/logout", s.get("access_token"), Map.of("refresh_token", s.get("refresh_token")),
						200);
		} finally {
			client.close();
		}
	}

	@Test
	@Timeout(90)
	void adminEngineerReadScopesAndSameJwtRevocationProtectBothCapabilities() throws Exception {
		String admin = login(ADMIN, PASSWORD), name = "analysis-engineer-" + UUID.randomUUID(),
				password = UUID.randomUUID().toString();
		String engineerId = call("POST", "/users", admin, Map.of("username", name, "password", password), 201).get("id")
				.asString();
		String engineer = login(name, password);
		var a = call("POST", "/applications", admin, Map.of("name", "analysis-a-" + UUID.randomUUID()), 201);
		var b = call("POST", "/applications", admin, Map.of("name", "analysis-b-" + UUID.randomUUID()), 201);
		String app = a.get("id").asString(), other = b.get("id").asString(),
				env = a.get("environments").get(0).get("id").asString(),
				second = a.get("environments").get(1).get("id").asString(),
				foreign = b.get("environments").get(0).get("id").asString();
		String assignment = "/applications/" + app + "/engineers/" + engineerId;
		call("POST", assignment, admin, null, 200);
		Instant time = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
		var event = UUID.randomUUID();
		var rows = List.of(row(app, env, event, time, "INFO"), row(app, second, event, time, "WARN"),
				row(other, foreign, event, time, "CRITICAL"));
		var statement = new StringBuilder("INSERT INTO logs FORMAT JSONEachRow\n");
		for (var row : rows)
			statement.append(json.writeValueAsString(row)).append('\n');
		writer.execute(statement.toString());
		String range = "from=" + time.minusSeconds(1) + "&to=" + time.plusSeconds(1);
		String logs = "/logs?" + range;
		String health = "/analytics/health?" + range;
		var page = call("GET", logs, engineer, null, 200);
		assertEquals(2, page.get("items").size());
		for (var r : page.get("items"))
			assertEquals(app, r.get("application_id").asString());
		String cursor = null;
		var identities = new HashSet<String>();
		int pages = 0;
		do {
			var data = call("GET",
					logs + "&application_id=" + app + "&limit=1" + (cursor == null ? "" : "&cursor=" + cursor),
					engineer, null, 200);
			for (var r : data.get("items"))
				assertTrue(identities.add(r.get("environment_id").asString() + r.get("level").asString()));
			cursor = data.get("next_cursor").isNull() ? null : data.get("next_cursor").asString();
			assertTrue(++pages <= 2);
		} while (cursor != null);
		assertEquals(2, identities.size());
		var metrics = call("GET", health, engineer, null, 200);
		assertEquals("hour", metrics.get("bucket").asString());
		assertEquals(2, metrics.get("items").size());
		for (var r : metrics.get("items")) {
			assertEquals(app, r.get("application_id").asString());
			assertEquals(0, r.get("log_error_rate").asDouble());
		}
		for (String route : List.of(logs, health)) {
			call("GET", route + "&application_id=" + other, engineer, null, 403);
			call("GET", route + "&application_id=" + app + "&environment_id=" + foreign, engineer, null, 403);
			call("GET", route + "&environment_id=" + foreign, engineer, null, 403);
			call("GET", route + "&application_id=" + other, admin, null, 200);
			call("GET", route, null, null, 401);
		}
		call("DELETE", assignment, admin, null, 200);
		for (String route : List.of(logs, health)) {
			call("GET", route + "&application_id=" + app, engineer, null, 403);
			assertTrue(call("GET", route, engineer, null, 200).get("items").isEmpty());
		}
	}

	private Map<String, Object> row(String app, String env, UUID event, Instant t, String level) {
		var r = new LinkedHashMap<String, Object>();
		r.put("event_id", event.toString());
		r.put("application_id", app);
		r.put("environment_id", env);
		r.put("host_ip", "127.0.0.1");
		r.put("level", level);
		r.put("message", "Analysis isolated verification");
		r.put("timestamp", t.toString().replace('T', ' ').replace("Z", ""));
		r.put("trace_id", UUID.randomUUID().toString());
		r.put("metadata_json", "{}");
		r.put("received_at", r.get("timestamp"));
		r.put("processed_at", r.get("timestamp"));
		return r;
	}
}
