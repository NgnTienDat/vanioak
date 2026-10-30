package com.h.vanioak.api.alert.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

public record IncidentStatusRequest(@NotNull @Pattern(regexp = "RESOLVED") String status) {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static IncidentStatusRequest read(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != 1 || !body.has("status")
				|| !body.get("status").isString() || !"RESOLVED".equals(body.get("status").asString()))
			throw new IllegalArgumentException("Only RESOLVED status is supported");
		return new IncidentStatusRequest("RESOLVED");
	}
}
