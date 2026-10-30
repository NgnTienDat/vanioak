package com.h.vanioak.api.identity.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.identity.api.UserFacade.Status;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AssignedEngineerListResponse(List<AssignedEngineerResponse> items,
		@JsonProperty("next_cursor") String nextCursor) {
	public record AssignedEngineerResponse(UUID id, String username, Status status) { }
}
