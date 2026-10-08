package com.h.vanioak.common.config.clickhouse;

import java.io.IOException;
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

	@Override
	public void close() {
		client.close();
	}
}
