package com.h.vanioak.modules.analysis.internal.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;

class ClickHouseReadClientTest {
	ClickHouseReadClient client(HttpServer server, long millis) {
		return new ClickHouseReadClient(
				new ClickHouseProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-only",
						"test-only", Duration.ofSeconds(1), Duration.ofMillis(millis)));
	}

	@Test
	void bindsValuesOutsideSqlAndReadsCompleteBody() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var uri = new AtomicReference<String>();
		var body = new AtomicReference<String>();
		server.createContext("/", e -> {
			uri.set(e.getRequestURI().getRawQuery());
			body.set(new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			byte[] b = "{}\n".getBytes();
			e.sendResponseHeaders(200, b.length);
			e.getResponseBody().write(b);
			e.close();
		});
		server.start();
		try (var c = client(server, 2000)) {
			assertEquals("{}\n", new String(c.query("SELECT {message:String}", Map.of("message", "x' OR 1=1 -- %_"))));
			assertEquals("SELECT {message:String}", body.get());
			assertTrue(uri.get().contains("param_message="));
			assertFalse(uri.get().contains(" OR "));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void stalledBodyHasBoundedFullResponseDeadline() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			e.sendResponseHeaders(200, 0);
			try {
				Thread.sleep(500);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			e.close();
		});
		server.start();
		try (var c = client(server, 60)) {
			long start = System.nanoTime();
			var ex = assertThrows(AnalysisException.class, () -> c.query("SELECT 1", Map.of()));
			assertEquals(ErrorCode.UNAVAILABLE, ex.getErrorCode());
			assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 450);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void responseExceptionHeaderAndHttpFailureAreSanitized() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			e.getResponseHeaders().add("X-ClickHouse-Exception-Code", "241");
			byte[] b = "sensitive-test-value".getBytes();
			e.sendResponseHeaders(200, b.length);
			e.getResponseBody().write(b);
			e.close();
		});
		server.start();
		try (var c = client(server, 2000)) {
			var ex = assertThrows(AnalysisException.class, () -> c.query("SELECT 1", Map.of()));
			assertFalse(ex.toString().contains("sensitive-test-value"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void oversizeBodyNon200AndNonSelectFailClosed() throws Exception {
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			try {
				e.sendResponseHeaders(200, 0);
				e.getResponseBody().write(new byte[ClickHouseReadClient.MAX_BYTES + 1]);
			} catch (java.io.IOException ignored) {
			} finally {
				e.close();
			}
		});
		server.start();
		var props = new com.h.vanioak.common.config.clickhouse.ClickHouseProperties(
				java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "user", "secret",
				java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3));
		try (var client = new ClickHouseReadClient(props)) {
			assertThrows(com.h.vanioak.modules.analysis.api.AnalysisException.class,
					() -> client.query("SELECT 1", java.util.Map.of()));
			assertThrows(IllegalArgumentException.class, () -> client.query("DELETE FROM logs", java.util.Map.of()));
		} finally {
			server.stop(0);
		}
		var unavailable = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		unavailable.createContext("/", e -> {
			e.sendResponseHeaders(503, 0);
			e.getResponseBody().write("secret raw response".getBytes());
			e.close();
		});
		unavailable.start();
		var failureProps = new com.h.vanioak.common.config.clickhouse.ClickHouseProperties(
				java.net.URI.create("http://127.0.0.1:" + unavailable.getAddress().getPort()), "user", "secret",
				java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
		try (var client = new ClickHouseReadClient(failureProps)) {
			var ex = assertThrows(com.h.vanioak.modules.analysis.api.AnalysisException.class,
					() -> client.query("SELECT 1", java.util.Map.of()));
			assertFalse(ex.getMessage().contains("secret"));
		} finally {
			unavailable.stop(0);
		}
	}
}
