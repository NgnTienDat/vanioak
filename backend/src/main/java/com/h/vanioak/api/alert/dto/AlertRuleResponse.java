package com.h.vanioak.api.alert.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Rule;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

public record AlertRuleResponse(UUID id, @JsonProperty("environment_id") UUID environmentId, Level level,
		int threshold, @JsonProperty("window_seconds") int windowSeconds,
		@JsonProperty("cooldown_seconds") int cooldownSeconds,
		@JsonProperty("resolve_after_seconds") int resolveAfterSeconds, boolean enabled,
		@JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {
	public static AlertRuleResponse from(Rule rule) {
		return new AlertRuleResponse(rule.id(), rule.environmentId(), rule.level(), rule.threshold(),
				rule.windowSeconds(), rule.cooldownSeconds(), rule.resolveAfterSeconds(), rule.enabled(),
				rule.createdAt(), rule.updatedAt());
	}

	public record Page(List<AlertRuleResponse> items, @JsonProperty("next_cursor") String nextCursor) { }
}
