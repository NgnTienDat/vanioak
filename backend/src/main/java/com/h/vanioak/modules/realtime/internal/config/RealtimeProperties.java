package com.h.vanioak.modules.realtime.internal.config;

import java.util.List;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "vanioak.realtime")
public record RealtimeProperties(boolean enabled, List<String> allowedOrigins, Path fencePath) {
	public RealtimeProperties(boolean enabled, List<String> allowedOrigins) {
		this(enabled, allowedOrigins, null);
	}

	@ConstructorBinding
	public RealtimeProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		
		if (allowedOrigins.stream().anyMatch(origin -> origin.isBlank() || origin.contains("*")))
			throw new IllegalArgumentException("Realtime CORS origins must be explicit");
	}
}
