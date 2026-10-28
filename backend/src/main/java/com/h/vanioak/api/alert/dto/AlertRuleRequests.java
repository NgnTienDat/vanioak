package com.h.vanioak.api.alert.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import tools.jackson.databind.JsonNode;

/** Strict scalar types at this boundary; no global Jackson coercion changes. */
public final class AlertRuleRequests {
	private AlertRuleRequests() { }

	public record Create(@JsonProperty("environment_id") @NotNull UUID environmentId, @NotNull Level level,
			@NotNull @Positive Integer threshold, @JsonProperty("window_seconds") @NotNull @Positive Integer windowSeconds,
			@JsonProperty("cooldown_seconds") @NotNull @Positive Integer cooldownSeconds,
			@JsonProperty("resolve_after_seconds") @NotNull @Positive Integer resolveAfterSeconds,
			@NotNull Boolean enabled) {
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static Create read(JsonNode body) {
			object(body);
			String environment = text(body, "environment_id");
			UUID id = environment == null ? null : UUID.fromString(environment);
			if (id != null && !id.toString().equalsIgnoreCase(environment)) throw new IllegalArgumentException();
			String level = text(body, "level");
			return new Create(id, level == null ? null : Level.valueOf(level), integer(body, "threshold"),
					integer(body, "window_seconds"), integer(body, "cooldown_seconds"),
					integer(body, "resolve_after_seconds"), bool(body, "enabled"));
		}
	}

	public record Update(@Positive Integer threshold, @JsonProperty("window_seconds") @Positive Integer windowSeconds,
			@JsonProperty("cooldown_seconds") @Positive Integer cooldownSeconds,
			@JsonProperty("resolve_after_seconds") @Positive Integer resolveAfterSeconds, Boolean enabled) {
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static Update read(JsonNode body) {
			object(body);
			return new Update(integer(body, "threshold"), integer(body, "window_seconds"),
					integer(body, "cooldown_seconds"), integer(body, "resolve_after_seconds"), bool(body, "enabled"));
		}
	}

	private static void object(JsonNode body) {
		if (body == null || !body.isObject()) throw new IllegalArgumentException();
	}

	private static JsonNode value(JsonNode body, String key) {
		JsonNode value = body.get(key);
		if (value != null && value.isNull()) throw new IllegalArgumentException();
		return value;
	}

	private static String text(JsonNode body, String key) {
		JsonNode value = value(body, key);
		if (value == null) return null;
		if (!value.isString()) throw new IllegalArgumentException();
		return value.asString();
	}

	private static Integer integer(JsonNode body, String key) {
		JsonNode value = value(body, key);
		if (value == null) return null;
		if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException();
		return value.intValue();
	}

	private static Boolean bool(JsonNode body, String key) {
		JsonNode value = value(body, key);
		if (value == null) return null;
		if (!value.isBoolean()) throw new IllegalArgumentException();
		return value.booleanValue();
	}
}
