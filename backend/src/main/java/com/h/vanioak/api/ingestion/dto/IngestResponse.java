package com.h.vanioak.api.ingestion.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record IngestResponse(boolean accepted, @JsonProperty("request_id") UUID requestId) { }
