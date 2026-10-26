package com.h.vanioak.modules.processing.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.BeanUtils;
import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ClickHouseLogWriterTest {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final AtomicReference<String> body = new AtomicReference<>();
	private final AtomicReference<String> authorization = new AtomicReference<>();
	private final AtomicReference<String> query = new AtomicReference<>();
	private final AtomicReference<String> method = new AtomicReference<>();
	private HttpServer server;
	private ClickHouseConnection connection;
	private ClickHouseLogWriter writer;
	private volatile int status = 200;
	private volatile String response = "";
	private volatile String exceptionCode;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			query.set(exchange.getRequestURI().getQuery());
			method.set(exchange.getRequestMethod());
			if (exceptionCode != null) {
				exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", exceptionCode);
			}
			exchange.getResponseHeaders().set("Location", "/redirect-target");
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		connection = new ClickHouseConnection(new ClickHouseProperties(
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-user", "test-password",
				Duration.ofSeconds(1), Duration.ofSeconds(1)));
		writer = new ClickHouseLogWriter(connection, mapper);
	}

	@AfterEach
	void stopServer() {
		connection.close();
		server.stop(0);
	}

	@Test
	void writesAllElevenFieldsWithSynchronousOptionsAndAuthenticatedUtf8Post() {
		NormalizedLog log = log();
		writer.write(List.of(log));
		String[] lines = body.get().split("\n");
		assertEquals(2, lines.length);
		assertEquals("INSERT INTO logs (event_id, application_id, environment_id, host_ip, level, message, "
				+ "timestamp, trace_id, metadata_json, received_at, processed_at) FORMAT JSONEachRow", lines[0]);
		var row = mapper.readTree(lines[1]);
		assertEquals(11, row.size());
		assertEquals(log.eventId().toString(), row.get("event_id").asString());
		assertEquals(log.applicationId().toString(), row.get("application_id").asString());
		assertEquals(log.environmentId().toString(), row.get("environment_id").asString());
		assertEquals(log.hostIp(), row.get("host_ip").asString());
		assertEquals(log.level(), row.get("level").asString());
		assertEquals(log.message(), row.get("message").asString());
		assertEquals(log.traceId(), row.get("trace_id").asString());
		assertTrue(row.get("metadata_json").isString());
		assertEquals(log.metadataJson(), row.get("metadata_json").asString());
		assertEquals("2026-10-10 01:02:03.123", row.get("timestamp").asString());
		assertEquals("2026-10-10 01:02:04.987", row.get("received_at").asString());
		assertEquals("2026-10-10 01:02:05.111", row.get("processed_at").asString());
		assertEquals(123456789, log.timestamp().getNano());
		assertEquals(987654321, log.receivedAt().getNano());
		assertEquals(111222333, log.processedAt().getNano());
		assertEquals("POST", method.get());
		assertEquals("async_insert=0&wait_end_of_query=1", query.get());
		assertEquals("Basic " + Base64.getEncoder().encodeToString(
				"test-user:test-password".getBytes(StandardCharsets.UTF_8)), authorization.get());
	}

	@Test
	void writesRecordsNormalizedFromTheActualIngestionBatch() {
		NormalizedLog source = log();
		var raw = new RawLogBatchEvent(UUID.randomUUID(), "raw.log", source.receivedAt(), "ingestion", 1,
				new RawLogBatchEvent.Payload(UUID.randomUUID(), source.applicationId(), source.environmentId(),
						source.receivedAt(), List.of(new RawLogBatchEvent.Log(source.eventId(), source.hostIp(),
								source.level(), source.message(), source.timestamp(), source.traceId(),
								Map.of("nested", List.of("value", 1, true))))));
		var normalized = normalizer(mapper, Clock.fixed(source.processedAt(), ZoneOffset.UTC))
				.normalize(mapper.writeValueAsBytes(raw));
		writer.write(normalized);
		var row = mapper.readTree(body.get().split("\n")[1]);
		assertEquals(source.eventId().toString(), row.get("event_id").asString());
		assertEquals(source.applicationId().toString(), row.get("application_id").asString());
		assertEquals(mapper.valueToTree(Map.of("nested", List.of("value", 1, true))),
				mapper.readTree(row.get("metadata_json").asString()));
	}

	@Test
	void rejectsNonSuccessRedirectAndErrorResponsesWithoutExposingServerText() {
		for (int code : List.of(400, 500, 302)) {
			status = code;
			response = "sensitive server response";
			assertSafeFailure();
		}
		status = 200;
		assertSafeFailure();
		response = "";
		exceptionCode = "27";
		assertSafeFailure();
		exceptionCode = "0";
		assertDoesNotThrow(() -> writer.write(List.of(log())));
	}

	@Test
	void preservesConnectivityCheckSemantics() throws Exception {
		response = "1\n";
		connection.checkConnectivity();
		assertEquals("SELECT 1", body.get());
		assertNull(query.get());
		response = "sensitive server response";
		IOException failure = assertThrows(IOException.class, connection::checkConnectivity);
		assertFalse(failure.getMessage().contains(response));
	}

	@Test
	void surfacesConnectionRefusal() {
		server.stop(0);
		assertSafeFailure();
	}

	@Test
	void surfacesTimeoutAndRestoresInterruptionWithoutRetainingCauses() throws Exception {
		var transport = mock(ClickHouseConnection.class);
		var isolatedWriter = new ClickHouseLogWriter(transport, mapper);
		doThrow(new HttpTimeoutException("sensitive timeout")).when(transport).execute(anyString());
		var timeout = assertThrows(ProcessingException.class, () -> isolatedWriter.write(List.of(log())));
		assertEquals(ErrorCode.PERSISTENCE_UNAVAILABLE, timeout.getErrorCode());
		assertNull(timeout.getCause());
		doThrow(new InterruptedException("sensitive interruption")).when(transport).execute(anyString());
		try {
			assertThrows(ProcessingException.class, () -> isolatedWriter.write(List.of(log())));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void rejectsInvalidInternalListsBeforeSending() {
		assertThrows(NullPointerException.class, () -> writer.write(null));
		assertThrows(NullPointerException.class, () -> writer.write(java.util.Arrays.asList(log(), null)));
		assertThrows(IllegalArgumentException.class, () -> writer.write(List.of()));
		assertThrows(IllegalArgumentException.class, () -> writer.write(java.util.Collections.nCopies(1001, log())));
		assertNull(body.get());
	}

	private void assertSafeFailure() {
		ProcessingException failure = assertThrows(ProcessingException.class, () -> writer.write(List.of(log())));
		assertEquals(ErrorCode.PERSISTENCE_UNAVAILABLE, failure.getErrorCode());
		assertEquals("Log persistence unavailable", failure.getMessage());
		assertNull(failure.getCause());
	}

	private NormalizedLog log() {
		return new NormalizedLog(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", "ERROR",
				"Lỗi \"quoted\"\nnext line", Instant.parse("2026-10-10T01:02:03.123456789Z"), "trace\n\"id",
				"{\"nested\":[1,true,null,{\"text\":\"value\"}]}", Instant.parse("2026-10-10T01:02:04.987654321Z"),
				Instant.parse("2026-10-10T01:02:05.111222333Z"));
	}

	private static RawLogNormalizer normalizer(ObjectMapper mapper, Clock clock) {
		try {
			return BeanUtils.instantiateClass(
					RawLogNormalizer.class.getDeclaredConstructor(ObjectMapper.class, Clock.class), mapper, clock);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(exception);
		}
	}
}
