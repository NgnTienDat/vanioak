package com.h.vanioak.modules.realtime.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface RealtimeFacade {

	Subscription subscribe(UUID actorId, Filters filters, Authorization authorization, Sink sink);

	Fanout publishLog(LogEvent event);

	Fanout publishIncident(IncidentEvent event);

	boolean ready();

	record Filters(UUID applicationId, UUID environmentId, String level) { }

	interface Authorization extends AutoCloseable {
		void requireSession();
		void requireExplicitScope();
		boolean mayRead(UUID applicationId, UUID environmentId);
		@Override void close();
	}

	interface Sink {
		void send(String eventName, String json) throws Exception;
		void heartbeat() throws Exception;
		CompletionStage<Void> close();
	}

	interface Subscription extends AutoCloseable {
		void activate();
		@Override void close();
	}

	record Fanout(boolean accounted, int queued, int closed) { }

	record LogEvent(UUID eventId, UUID applicationId, UUID environmentId, String hostIp, String level,
			String message, Instant timestamp, String traceId, Map<String, Object> metadata) { }

	record IncidentEvent(UUID eventId, String type, Instant occurredAt, String producer, int schemaVersion,
			UUID incidentId, UUID applicationId, UUID environmentId,
			String level, String status, String message, Long errorCount, Instant lastSeenAt, Instant resolvedAt) { }
}
