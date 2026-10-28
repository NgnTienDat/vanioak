package com.h.vanioak.modules.alert.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface IncidentReadFacade {
	Page list(Scope scope, Filters filters);
	Optional<Detail> detail(Scope scope, UUID id);

	/** Server-created scope, never bound from an HTTP body or cursor. */
	record Scope(UUID actorId, Set<UUID> environmentIds, UUID requestedApplication, UUID requestedEnvironment) {
		public Scope { environmentIds = Set.copyOf(environmentIds); }
	}
	enum Status { OPEN, RESOLVED }
	record Filters(Status status, AlertRuleFacade.Level level, Instant from, Instant to, String cursor, int limit) { }
	record Item(UUID id, UUID environmentId, AlertRuleFacade.Level level, Status status, String fingerprint,
			String message, long errorCount, Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt) { }
	record Event(UUID id, String eventType, Instant eventAt, Long errorCount, String message, Map<String,Object> metadata) { }
	record Page(List<Item> items, String nextCursor) { }
	record Detail(Item incident, List<Event> events) { }
}
