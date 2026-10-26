package com.h.vanioak.api.analysis.dto;

import java.util.List;

public record HealthAnalyticsResponse(String bucket, List<HealthMetricResponse> items) {
}
