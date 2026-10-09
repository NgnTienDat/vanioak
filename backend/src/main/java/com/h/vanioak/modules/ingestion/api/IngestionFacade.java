package com.h.vanioak.modules.ingestion.api;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;

public interface IngestionFacade {
	VerificationResult authenticate(String rawApiKey);
	void validateBinding(VerificationResult context, List<LogScope> scopes);
	Acceptance ingest(VerificationResult context, List<LogEntry> logs);

	record LogScope(String application, String environment) { }
	record LogEntry(String application, String environment, String hostIp, String level, String message,
			Instant timestamp, String traceId, Map<String, ?> metadata) { }
	record Acceptance(UUID requestId, int acceptedCount) { }
}
