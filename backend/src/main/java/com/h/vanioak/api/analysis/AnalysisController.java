package com.h.vanioak.api.analysis;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.analysis.dto.LogRecordResponse;
import com.h.vanioak.api.analysis.dto.LogListResponse;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AnalysisController {
	private final AnalysisApiService api;

	@GetMapping("/logs")
	public ApiResponse<LogListResponse> search(Authentication caller,
			@RequestParam(required = false) UUID application_id, @RequestParam(required = false) UUID environment_id,
			@RequestParam(required = false) String level, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) String trace_id,
			@RequestParam(required = false) String message, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		var page = api.search(caller, application_id, environment_id,
				new SearchFilters(level, from, to, trace_id, message, cursor, limit));
		return ApiResponse.success(
				new LogListResponse(page.items().stream().map(LogRecordResponse::from).toList(), page.nextCursor()));
	}

	@GetMapping("/analytics/health")
	public ApiResponse<com.h.vanioak.api.analysis.dto.HealthAnalyticsResponse> health(Authentication caller,
			@RequestParam(required = false) UUID application_id, @RequestParam(required = false) UUID environment_id,
			@RequestParam Instant from, @RequestParam Instant to, @RequestParam(defaultValue = "hour") String bucket) {
		var result = api.health(caller, application_id, environment_id,
				new com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthFilters(from, to, bucket));
		return ApiResponse.success(new com.h.vanioak.api.analysis.dto.HealthAnalyticsResponse(result.bucket(),
				result.items().stream().map(com.h.vanioak.api.analysis.dto.HealthMetricResponse::from).toList()));
	}
}
