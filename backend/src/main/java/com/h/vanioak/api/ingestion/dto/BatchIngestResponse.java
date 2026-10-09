package com.h.vanioak.api.ingestion.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record BatchIngestResponse(boolean accepted, @JsonProperty("accepted_count") int acceptedCount,
		@JsonProperty("request_id") UUID requestId) { }
