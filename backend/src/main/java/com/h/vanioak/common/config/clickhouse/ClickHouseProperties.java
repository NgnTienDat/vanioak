package com.h.vanioak.common.config.clickhouse;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vanioak.clickhouse")
public class ClickHouseProperties {

	private final URI url;
	private final String username;
	private final String password;
	private final Duration connectTimeout;
	private final Duration requestTimeout;

	public ClickHouseProperties(URI url, String username, String password,
			Duration connectTimeout, Duration requestTimeout) {
		if (url == null || !("http".equalsIgnoreCase(url.getScheme())
				|| "https".equalsIgnoreCase(url.getScheme())) || url.getHost() == null
				|| url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
				|| !(url.getPath().isEmpty() || "/".equals(url.getPath()))) {
			throw new IllegalArgumentException("CLICKHOUSE_URL must be an HTTP(S) server URL without credentials, query or path");
		}
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IllegalArgumentException("ClickHouse username and password are required");
		}
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
				|| requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
			throw new IllegalArgumentException("ClickHouse timeouts must be positive");
		}
		this.url = url;
		this.username = username;
		this.password = password;
		this.connectTimeout = connectTimeout;
		this.requestTimeout = requestTimeout;
	}

	public URI url() {
		return url;
	}

	public String username() {
		return username;
	}

	public String password() {
		return password;
	}

	public Duration connectTimeout() {
		return connectTimeout;
	}

	public Duration requestTimeout() {
		return requestTimeout;
	}
}
