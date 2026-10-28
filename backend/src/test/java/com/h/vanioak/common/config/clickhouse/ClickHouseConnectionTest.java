package com.h.vanioak.common.config.clickhouse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class ClickHouseConnectionTest {
	private HttpServer server;
	private final AtomicReference<HttpHandler> handler = new AtomicReference<>();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> handler.get().handle(exchange));
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void readOnlyQueryReusesCredentialsAndReturnsBoundedResult() throws Exception {
		handler.set(exchange -> {
			assertEquals("POST", exchange.getRequestMethod());
			assertEquals("Basic dXNlcjpwYXNzd29yZA==", exchange.getRequestHeaders().getFirst("Authorization"));
			assertEquals("wait_end_of_query=1", exchange.getRequestURI().getQuery());
			assertEquals("SELECT create_table_query FROM system.tables", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"create_table_query\":\"CREATE TABLE logs\"}\n", null);
		});
		try (var connection = connection(Duration.ofSeconds(1))) {
			assertTrue(connection.queryReadOnly("SELECT create_table_query FROM system.tables").contains("CREATE TABLE logs"));
		}
	}

	@Test
	void rejectsNonSelectBeforeNetwork() throws Exception {
		try (var connection = connection(Duration.ofSeconds(1))) {
			assertThrows(IllegalArgumentException.class, () -> connection.queryReadOnly("DELETE FROM logs"));
			assertThrows(IllegalArgumentException.class, () -> connection.queryReadOnly("SELECT 1; DELETE FROM logs"));
		}
	}

	@Test
	void oversizedResultIsRejected() throws Exception {
		handler.set(exchange -> respond(exchange, 200, "x".repeat(20_000), null));
		try (var connection = connection(Duration.ofSeconds(1))) {
			IOException error = assertThrows(IOException.class, () -> connection.queryReadOnly("SELECT 1"));
			assertFalse(error.getMessage().contains("xxxxx"));
		}
	}

	@Test
	void httpAndClickHouseExceptionHeadersCannotPass() throws Exception {
		try (var connection = connection(Duration.ofSeconds(1))) {
			handler.set(exchange -> respond(exchange, 500, "private server response", null));
			assertFalse(assertThrows(IOException.class, () -> connection.queryReadOnly("SELECT 1"))
					.getMessage().contains("private server response"));
			handler.set(exchange -> respond(exchange, 200, "private server response", "241"));
			assertFalse(assertThrows(IOException.class, () -> connection.queryReadOnly("SELECT 1"))
					.getMessage().contains("private server response"));
		}
	}

	@Test
	void timeoutIsBounded() throws Exception {
		var release = new CountDownLatch(1);
		handler.set(exchange -> {
			try {
				release.await(2, TimeUnit.SECONDS);
				respond(exchange, 200, "late", null);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		try (var connection = connection(Duration.ofMillis(150))) {
			assertThrows(IOException.class, () -> connection.queryReadOnly("SELECT 1"));
		} finally {
			release.countDown();
		}
	}

	@Test
	void existingConnectivityAndWritePathsRemainUsable() throws Exception {
		handler.set(exchange -> {
			String statement = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			respond(exchange, 200, statement.equals("SELECT 1") ? "1\n" : "", null);
		});
		try (var connection = connection(Duration.ofSeconds(1))) {
			connection.checkConnectivity();
			connection.execute("INSERT INTO logs FORMAT JSONEachRow\n");
		}
	}

	private ClickHouseConnection connection(Duration timeout) {
		return new ClickHouseConnection(new ClickHouseProperties(
				java.net.URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()),
				"user", "password", Duration.ofSeconds(1), timeout));
	}

	private static void respond(HttpExchange exchange, int status, String body, String exceptionCode) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		if (exceptionCode != null)
			exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", exceptionCode);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}
}
