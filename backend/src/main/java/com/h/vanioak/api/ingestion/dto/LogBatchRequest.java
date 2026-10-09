package com.h.vanioak.api.ingestion.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LogBatchRequest(@NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid LogRequest> logs) {
}
