package com.h.vanioak.api.alert.dto;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;
import com.h.vanioak.modules.alert.api.IncidentResolutionFacade.Source;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record IncidentStatusResponse(UUID id, Status status,
		@JsonProperty("resolved_at") Instant resolvedAt,
		@JsonProperty("resolution_source") Source resolutionSource,
		@JsonProperty("resolved_by_user_id") UUID resolvedByUserId) { }
