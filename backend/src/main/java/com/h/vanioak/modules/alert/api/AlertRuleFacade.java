package com.h.vanioak.modules.alert.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Use cases called with authorization established by the Backend API boundary. */
public interface AlertRuleFacade {

	Rule create(UUID createdBy, Create command);
	Page list(String cursor, int limit);
	Rule get(UUID id);
	Rule update(UUID id, Update command);
	Rule disable(UUID id);

	enum Level { ERROR, CRITICAL }
	record Create(UUID environmentId, Level level, Integer threshold, Integer windowSeconds,
			Integer cooldownSeconds, Integer resolveAfterSeconds, Boolean enabled) { }
	record Update(Integer threshold, Integer windowSeconds, Integer cooldownSeconds,
			Integer resolveAfterSeconds, Boolean enabled) { }
	record Rule(UUID id, UUID environmentId, Level level, int threshold, int windowSeconds,
			int cooldownSeconds, int resolveAfterSeconds, boolean enabled, Instant createdAt, Instant updatedAt) { }
	record Page(List<Rule> items, String nextCursor) { }
}
