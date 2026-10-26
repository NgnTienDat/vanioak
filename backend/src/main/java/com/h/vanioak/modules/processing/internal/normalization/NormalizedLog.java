package com.h.vanioak.modules.processing.internal.normalization;

import java.time.Instant;
import java.util.UUID;

public record NormalizedLog(UUID eventId, UUID applicationId, UUID environmentId, String hostIp, String level,
		String message, Instant timestamp, String traceId, String metadataJson, Instant receivedAt, Instant processedAt) {
}
