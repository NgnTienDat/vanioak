package com.h.vanioak.api.alert.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;

public record IncidentResponse(UUID id, Ref application, Ref environment, Level level, Status status,
		String fingerprint, String message, @JsonProperty("error_count") long errorCount,
		@JsonProperty("first_seen_at") Instant firstSeenAt, @JsonProperty("last_seen_at") Instant lastSeenAt,
		@JsonProperty("resolved_at") Instant resolvedAt, @JsonInclude(JsonInclude.Include.NON_NULL) List<History> events) {
	public record Ref(UUID id, String name) { }
	public record History(UUID id, @JsonProperty("event_type") String eventType, @JsonProperty("event_at") Instant eventAt,
			@JsonProperty("error_count") Long errorCount, String message, Map<String,Object> metadata) { }
	public record Page(List<IncidentResponse> items, @JsonProperty("next_cursor") String nextCursor) { }
}
