package com.h.vanioak.api.ingestion.dto;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;

import tools.jackson.databind.JsonNode;

public record LogRequest(
		@NotNull @Size(max = 100) String application,
		@NotNull @Pattern(regexp = "DEV|TEST|STAGING|dev|test|staging") String environment,
		@NotNull @JsonProperty("host_ip") String hostIp,
		@NotNull @Pattern(regexp = "INFO|WARN|ERROR|CRITICAL") String level,
		@NotNull String message,
		@NotNull Instant timestamp,
		@NotNull @JsonProperty("trace_id") String traceId,
		Map<String, JsonNode> metadata) {

	@JsonCreator
	public static LogRequest fromJson(
			@JsonProperty("application") JsonNode application,
			@JsonProperty("environment") JsonNode environment,
			@JsonProperty("host_ip") JsonNode hostIp,
			@JsonProperty("level") JsonNode level,
			@JsonProperty("message") JsonNode message,
			@JsonProperty("timestamp") JsonNode timestamp,
			@JsonProperty("trace_id") JsonNode traceId,
			@JsonProperty("metadata") JsonNode metadata) {
		Map<String, JsonNode> properties = null;
		if (metadata != null) {
			if (!metadata.isObject()) throw new IllegalArgumentException("Log metadata must be an object");
			properties = new LinkedHashMap<>();
			for (var entry : metadata.properties()) properties.put(entry.getKey(), entry.getValue());
		}
		String time = text(timestamp);
		Instant instant = null;
		if (time != null) {
			try {
				instant = Instant.parse(time);
			} catch (DateTimeParseException ex) {
				throw new IllegalArgumentException("Log timestamp must be an offset date-time");
			}
		}
		return new LogRequest(text(application), text(environment), text(hostIp), text(level), text(message),
				instant, text(traceId), properties);
	}

	private static String text(JsonNode value) {
		if (value == null || value.isNull()) return null;
		if (!value.isString()) throw new IllegalArgumentException("Log property must be a string");
		return value.asString();
	}
}
