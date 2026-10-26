package com.h.vanioak.api.analysis.dto;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record HealthMetricResponse(@JsonProperty("application_id") UUID applicationId,
		@JsonProperty("environment_id") UUID environmentId, Instant timestamp,
		@JsonProperty("total_logs") long totalLogs, @JsonProperty("error_logs") long errorLogs,
		@JsonProperty("critical_logs") long criticalLogs, @JsonProperty("log_error_rate") double logErrorRate) {
	public static HealthMetricResponse from(com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthMetric v) {
		return new HealthMetricResponse(v.applicationId(), v.environmentId(), v.timestamp(), v.totalLogs(),
				v.errorLogs(), v.criticalLogs(), v.logErrorRate());
	}
}
