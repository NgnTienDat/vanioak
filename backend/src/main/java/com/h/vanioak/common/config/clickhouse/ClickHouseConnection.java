package com.h.vanioak.common.config.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ClickHouseConnection implements AutoCloseable {

	private final HttpClient client;
	private final HttpRequest request;

	public ClickHouseConnection(ClickHouseProperties properties) {
		this.client = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		String credentials = properties.username() + ":" + properties.password();
		this.request = HttpRequest.newBuilder(properties.url())
				.timeout(properties.requestTimeout())
				.header("Authorization", "Basic " + Base64.getEncoder()
						.encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
				.POST(HttpRequest.BodyPublishers.ofString("SELECT 1"))
				.build();
	}

	public void checkConnectivity() throws IOException, InterruptedException {
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || !"1".equals(response.body().strip())) {
			// Do not include the server response: it can contain sensitive configuration.
			throw new IOException("ClickHouse connectivity check failed (HTTP " + response.statusCode() + ")");
		}
	}

	public void execute(String statement) throws IOException, InterruptedException {
		HttpRequest insert = HttpRequest.newBuilder(request, (name, value) -> true)
				.uri(URI.create(request.uri() + "?async_insert=0&wait_end_of_query=1"))
				.header("Content-Type", "text/plain; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(statement, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = client.send(insert, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != 200 || !response.body().isBlank()
				|| response.headers().allValues("X-ClickHouse-Exception-Code").stream()
						.anyMatch(code -> !"0".equals(code))) {
			throw new IOException("ClickHouse statement execution failed");
		}
	}

	@Override
	public void close() {
		client.close();
	}
}
