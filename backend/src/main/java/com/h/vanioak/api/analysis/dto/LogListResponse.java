package com.h.vanioak.api.analysis.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LogListResponse(List<LogRecordResponse> items, @JsonProperty("next_cursor") String nextCursor) {
}
