package com.h.vanioak.api.identity.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiKeyListResponse(List<ApiKeyResponse> items, @JsonProperty("next_cursor") String nextCursor) { }

