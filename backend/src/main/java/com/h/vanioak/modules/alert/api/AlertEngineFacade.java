package com.h.vanioak.modules.alert.api;

import java.time.Instant;
import java.util.UUID;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

/** Trusted critical-event input; a successful result is returned only after commit. */
public interface AlertEngineFacade {
	Result evaluate(CriticalEvent event);
	int resolveInactive(int limit);

	record CriticalEvent(UUID eventId, UUID environmentId, Level level, String message, Instant receivedAt) { }
	enum Decision { ACCOUNTED, DUPLICATE, OPENED, UPDATED }
	record Result(Decision decision, UUID incidentId, long errorCount) { }
}
