package com.h.vanioak.modules.analysis.api;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AnalysisFacade {
	SearchPage search(AuthorizedScope scope, SearchFilters filters);

	HealthResult health(AuthorizedScope scope, HealthFilters filters);

	record HealthFilters(Instant from, Instant to, String bucket) {
	}

	record HealthMetric(UUID applicationId, UUID environmentId, Instant timestamp, long totalLogs, long errorLogs,
			long criticalLogs, double logErrorRate) {
	}

	record HealthResult(String bucket, List<HealthMetric> items) {
	}

	record SearchFilters(String level, Instant from, Instant to, String traceId, String message, String cursor,
			int limit) {
	}

	record LogView(UUID eventId, UUID applicationId, UUID environmentId, String hostIp, String level, String message,
			Instant timestamp, String traceId, Map<String, Object> metadata, Instant receivedAt, Instant processedAt) {
	}

	record SearchPage(List<LogView> items, String nextCursor) {
	}

	record TimeRange(Instant from, Instant to) {
		public TimeRange {
			if (from == null || to == null || !from.isBefore(to)
					|| Duration.between(from, to).compareTo(Duration.ofDays(7)) > 0
					|| from.isBefore(Instant.parse("1900-01-01T00:00:00Z"))
					|| !to.isBefore(Instant.parse("2300-01-01T00:00:00Z")))
				throw new AnalysisException(ErrorCode.INVALID_QUERY);
		}

		public static long ceilMillis(Instant value) {
			return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1000),
					(value.getNano() + 999999L) / 1000000);
		}

		public long fromMillis() {
			return ceilMillis(from);
		}

		public long toMillis() {
			return ceilMillis(to);
		}
	}
}
