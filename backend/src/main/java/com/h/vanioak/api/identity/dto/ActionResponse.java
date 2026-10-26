package com.h.vanioak.api.identity.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ActionResponse(@JsonProperty("user_id") UUID userId, @JsonProperty("application_id") UUID applicationId) { }
