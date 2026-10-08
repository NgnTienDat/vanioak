package com.h.vanioak.api.identity.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationResponse(UUID id, String name, String description, Status status,
		List<EnvironmentSummary> environments) {
	public record EnvironmentSummary(UUID id, EnvironmentName name, Status status) { }
}

