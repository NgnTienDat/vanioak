package com.h.vanioak.api.analysis.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.LogView;

public record LogRecordResponse(@JsonProperty("event_id") UUID eventId,
		@JsonProperty("application_id") UUID applicationId, @JsonProperty("environment_id") UUID environmentId,
		@JsonProperty("host_ip") String hostIp, String level, String message, Instant timestamp,
		@JsonProperty("trace_id") String traceId, Map<String, Object> metadata,
		@JsonProperty("received_at") Instant receivedAt, @JsonProperty("processed_at") Instant processedAt) {
	public static LogRecordResponse from(LogView v) {
		return new LogRecordResponse(v.eventId(), v.applicationId(), v.environmentId(), v.hostIp(), v.level(),
				v.message(), v.timestamp(), v.traceId(), v.metadata(), v.receivedAt(), v.processedAt());
	}
}
