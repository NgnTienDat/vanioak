package com.h.vanioak.modules.analysis.internal.query;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;

public class ClickHouseReadClient implements AutoCloseable {
	public static final int MAX_BYTES = 16 * 1024 * 1024;
	private final ClickHouseProperties properties;
	private final HttpClient client;
	private final Duration timeout;

	public ClickHouseReadClient(ClickHouseProperties properties) {
		this.properties = properties;
		this.timeout = properties.requestTimeout().compareTo(Duration.ofSeconds(5)) < 0 ? properties.requestTimeout()
				: Duration.ofSeconds(5);
		client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	public byte[] query(String sql, Map<String, String> parameters) {
		if (!sql.startsWith("SELECT ") || sql.indexOf(';') >= 0)
			throw new IllegalArgumentException("Only fixed SELECT statements are supported");
		var uri = new StringBuilder(properties.url().toString()).append("?wait_end_of_query=1");
		parameters.forEach((key, value) -> {
			if (!key.matches("[a-z_]+"))
				throw new IllegalArgumentException("Invalid parameter name");
			uri.append("&param_").append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
		});
		if (uri.length() > 96 * 1024)
			throw new AnalysisException(ErrorCode.INVALID_QUERY);
		var request = HttpRequest.newBuilder(URI.create(uri.toString())).timeout(timeout)
				.header("Authorization",
						"Basic " + Base64.getEncoder().encodeToString(
								(properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8)))
				.POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8)).build();
		CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody(MAX_BYTES));
		try {
			var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
			if (response.statusCode() != 200 || response.headers().allValues("X-ClickHouse-Exception-Code").stream()
					.anyMatch(s -> !"0".equals(s.trim())))
				throw new AnalysisException(ErrorCode.UNAVAILABLE);
			return response.body();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AnalysisException(ErrorCode.UNAVAILABLE);
		} catch (Exception ex) {
			throw new AnalysisException(ErrorCode.UNAVAILABLE);
		} finally {
			pending.cancel(true);
		}
	}

	@Override
	public void close() {
		client.close();
	}

	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private final int limit;
		private Flow.Subscription subscription;

		LimitedBody(int limit) {
			this.limit = limit;
		}

		public CompletionStage<byte[]> getBody() {
			return body;
		}

		public void onSubscribe(Flow.Subscription value) {
			subscription = value;
			value.request(1);
		}

		public void onNext(List<ByteBuffer> buffers) {
			for (var b : buffers) {
				if (body.isDone() || b.remaining() > limit - output.size()) {
					subscription.cancel();
					body.completeExceptionally(new IllegalStateException("Response limit exceeded"));
					return;
				}
				byte[] bytes = new byte[b.remaining()];
				b.get(bytes);
				output.writeBytes(bytes);
			}
			subscription.request(1);
		}

		public void onError(Throwable error) {
			body.completeExceptionally(error);
		}

		public void onComplete() {
			body.complete(output.toByteArray());
		}
	}
}
