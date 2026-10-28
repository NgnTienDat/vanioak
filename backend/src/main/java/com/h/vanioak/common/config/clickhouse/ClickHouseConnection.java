package com.h.vanioak.common.config.clickhouse;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ClickHouseConnection implements AutoCloseable {
	private static final int MAX_READ_BYTES = 16 * 1024;

	private final HttpClient client;
	private final HttpRequest request;
	private final Duration requestTimeout;

	public ClickHouseConnection(ClickHouseProperties properties) {
		this.requestTimeout = properties.requestTimeout();
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

	public String queryReadOnly(String statement) throws IOException, InterruptedException {
		if (!statement.startsWith("SELECT ") || statement.indexOf(';') >= 0)
			throw new IllegalArgumentException("Only one SELECT statement is allowed");
		HttpRequest query = HttpRequest.newBuilder(request, (name, value) -> true)
				.uri(URI.create(request.uri() + "?wait_end_of_query=1"))
				.header("Content-Type", "text/plain; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(statement, StandardCharsets.UTF_8))
				.build();
		CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(query, info -> new LimitedBody());
		try {
			HttpResponse<byte[]> response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
			if (response.statusCode() != 200 || response.headers().allValues("X-ClickHouse-Exception-Code")
					.stream().anyMatch(code -> !"0".equals(code)))
				throw new IOException("ClickHouse metadata read failed");
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(response.body())).toString();
		} catch (ExecutionException | TimeoutException | CharacterCodingException ex) {
			throw new IOException("ClickHouse metadata read failed");
		} finally {
			pending.cancel(true);
		}
	}

	@Override
	public void close() {
		client.close();
	}

	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private Flow.Subscription subscription;

		public CompletionStage<byte[]> getBody() { return result; }

		public void onSubscribe(Flow.Subscription value) {
			subscription = value;
			value.request(1);
		}

		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if (result.isDone() || buffer.remaining() > MAX_READ_BYTES - bytes.size()) {
					subscription.cancel();
					result.completeExceptionally(new IOException("ClickHouse metadata response too large"));
					return;
				}
				byte[] next = new byte[buffer.remaining()];
				buffer.get(next);
				bytes.writeBytes(next);
			}
			subscription.request(1);
		}

		public void onError(Throwable error) { result.completeExceptionally(error); }
		public void onComplete() { result.complete(bytes.toByteArray()); }
	}
}
