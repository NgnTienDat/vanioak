package com.h.vanioak.modules.ingestion.api.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RawLogBatchEvent(
		@JsonProperty("event_id") UUID eventId,
		@JsonProperty("event_type") String eventType,
		@JsonProperty("occurred_at") Instant occurredAt,
		String producer,
		@JsonProperty("schema_version") int schemaVersion,
		Payload payload) {
	public record Payload(@JsonProperty("request_id") UUID requestId,
			@JsonProperty("application_id") UUID applicationId,
			@JsonProperty("environment_id") UUID environmentId,
			@JsonProperty("received_at") Instant receivedAt, List<Log> logs) { }

	public record Log(@JsonProperty("event_id") UUID eventId,
			@JsonProperty("host_ip") String hostIp, String level, String message, Instant timestamp,
			@JsonProperty("trace_id") String traceId, Map<String, ?> metadata) { }
}
