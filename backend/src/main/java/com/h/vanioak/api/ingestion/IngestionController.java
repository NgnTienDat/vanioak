package com.h.vanioak.api.ingestion;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.ingestion.dto.LogBatchRequest;
import com.h.vanioak.api.ingestion.dto.LogRequest;
import com.h.vanioak.api.ingestion.dto.IngestResponse;
import com.h.vanioak.api.ingestion.dto.BatchIngestResponse;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;
import com.h.vanioak.modules.ingestion.api.IngestionFacade.LogEntry;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/logs")
@Tag(name = "Log Ingestion")
@SecurityRequirement(name = "apiKeyAuth")
@RequiredArgsConstructor
public class IngestionController {
	private final IngestionFacade ingestion;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Accepted by RabbitMQ"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Service unavailable") })
	public ResponseEntity<ApiResponse<IngestResponse>> ingest(@Valid @RequestBody LogRequest request,
			@RequestHeader("X-API-Key") String apiKey,
			@Parameter(hidden = true) @AuthenticationPrincipal VerificationResult context) {
		var result = ingestion.ingest(context, List.of(command(request)));
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(ApiResponse.success(new IngestResponse(true, result.requestId())));
	}

	@PostMapping(path = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Accepted by RabbitMQ"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Service unavailable") })
	public ResponseEntity<ApiResponse<BatchIngestResponse>> ingestBatch(@Valid @RequestBody LogBatchRequest request,
			@RequestHeader("X-API-Key") String apiKey,
			@Parameter(hidden = true) @AuthenticationPrincipal VerificationResult context) {
		var result = ingestion.ingest(context, request.logs().stream().map(this::command).toList());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
				new BatchIngestResponse(true, result.acceptedCount(), result.requestId())));
	}

	private LogEntry command(LogRequest request) {
		return new LogEntry(
				request.application(),
				request.environment(),
				request.hostIp(),
				request.level(),
				request.message(),
				request.timestamp(), request.traceId(), request.metadata());
	}
}
