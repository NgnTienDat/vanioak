package com.h.vanioak.modules.processing.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClickHouseLogWriterIT {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private ClickHouseProperties properties;
	private ClickHouseConnection connection;

	@BeforeEach
	void isolatedConnection() {
		properties = new ClickHouseProperties(URI.create(setting("URL")), setting("USERNAME"), setting("PASSWORD"),
				Duration.ofSeconds(5), Duration.ofSeconds(5));
		connection = new ClickHouseConnection(properties);
	}

	@AfterEach
	void close() {
		if (connection != null) {
			connection.close();
		}
	}

	@Test
	void synchronousInsertIsSearchableWithAllContractFieldsAndMillisecondPrecision() throws Exception {
		// The dedicated test server must already contain the unchanged contract table; no DDL or cleanup here.
		connection.checkConnectivity();
		Instant now = Instant.now();
		var logs = List.of(log(now), log(now.minus(1, ChronoUnit.DAYS)));
		new ClickHouseLogWriter(connection, mapper).write(logs);
		String ids = logs.stream().map(log -> "'" + log.eventId() + "'").collect(Collectors.joining(","));
		String sql = "SELECT event_id, application_id, environment_id, host_ip, level, message, timestamp, trace_id, "
				+ "metadata_json, received_at, processed_at FROM logs WHERE event_id IN (" + ids + ") FORMAT JSONEachRow";
		Map<String, JsonNode> stored = query(sql).lines().map(mapper::readTree)
				.collect(Collectors.toMap(row -> row.get("event_id").asString(), Function.identity()));
		assertEquals(logs.size(), stored.size());
		for (NormalizedLog log : logs) {
			JsonNode row = stored.get(log.eventId().toString());
			assertNotNull(row);
			assertEquals(11, row.size());
			assertEquals(log.applicationId().toString(), row.get("application_id").asString());
			assertEquals(log.environmentId().toString(), row.get("environment_id").asString());
			assertEquals(log.hostIp(), row.get("host_ip").asString());
			assertEquals(log.level(), row.get("level").asString());
			assertEquals(log.message(), row.get("message").asString());
			assertEquals(log.traceId(), row.get("trace_id").asString());
			assertEquals(log.metadataJson(), row.get("metadata_json").asString());
			assertEquals(log.timestamp().truncatedTo(ChronoUnit.MILLIS), instant(row, "timestamp"));
			assertEquals(log.receivedAt().truncatedTo(ChronoUnit.MILLIS), instant(row, "received_at"));
			assertEquals(log.processedAt().truncatedTo(ChronoUnit.MILLIS), instant(row, "processed_at"));
		}
	}

	@Test
	void rejectedInsertIsNotReportedAsSuccess() {
		IOException failure = assertThrows(IOException.class, () -> connection.execute(
				"INSERT INTO logs (unknown_phase2_test_column) VALUES (1)"));
		assertEquals("ClickHouse statement execution failed", failure.getMessage());
	}

	private String query(String statement) throws Exception {
		String credentials = properties.username() + ":" + properties.password();
		var request = HttpRequest.newBuilder(properties.url()).timeout(properties.requestTimeout())
				.header("Authorization", "Basic " + Base64.getEncoder()
						.encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
				.POST(HttpRequest.BodyPublishers.ofString(statement, StandardCharsets.UTF_8)).build();
		try (var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build()) {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(200, response.statusCode(), "Isolated ClickHouse query must succeed");
			return response.body();
		}
	}

	private Instant instant(JsonNode row, String field) {
		return Instant.parse(row.get(field).asString().replace(' ', 'T') + "Z");
	}

	private NormalizedLog log(Instant timestamp) {
		return new NormalizedLog(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", "ERROR",
				"Phase 2 isolated test: \"quoted\"\nUTF-8: lỗi", timestamp, UUID.randomUUID().toString(),
				"{\"nested\":[1,true,null]}", timestamp.plusMillis(1), timestamp.plusMillis(2));
	}

	private static String setting(String suffix) {
		String value = System.getenv("PROCESSING_CLICKHOUSE_TEST_" + suffix);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Explicit isolated ClickHouse test settings are required");
		}
		return value;
	}
}
