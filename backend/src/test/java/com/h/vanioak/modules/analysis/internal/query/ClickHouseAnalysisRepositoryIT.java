package com.h.vanioak.modules.analysis.internal.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;
import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.LogView;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthMetric;
import com.h.vanioak.modules.analysis.internal.search.LogCursorCodec;
import com.h.vanioak.modules.analysis.internal.search.LogSearchService;

class ClickHouseAnalysisRepositoryIT {
	@Test
	@Timeout(60)
	void finalCompositePaginationFiltersAndUtcAggregatesUseOwnedRows() throws Exception {
		if (!"true".equals(System.getenv("ANALYSIS_IT_APPROVED"))
				|| !"http://127.0.0.1:28123".equals(System.getenv("PROCESSING_CLICKHOUSE_TEST_URL")))
			throw new IllegalStateException("Require separately approved isolated Analysis fixture");
		var props = new ClickHouseProperties(URI.create(System.getenv("PROCESSING_CLICKHOUSE_TEST_URL")),
				Objects.requireNonNull(System.getenv("PROCESSING_CLICKHOUSE_TEST_USERNAME")),
				Objects.requireNonNull(System.getenv("PROCESSING_CLICKHOUSE_TEST_PASSWORD")), Duration.ofSeconds(2),
				Duration.ofSeconds(5));
		var json = new ObjectMapper();
		var app = UUID.randomUUID();
		var other = UUID.randomUUID();
		var env = UUID.randomUUID();
		var secondEnv = UUID.randomUUID();
		var event = UUID.randomUUID();
		var time = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(1800).plusMillis(123);
		var rows = new ArrayList<Map<String, Object>>();
		rows.add(row(app, env, event, time, "INFO", "normal"));
		rows.add(row(app, env, event, time, "WARN", "normal"));
		rows.add(row(app, secondEnv, event, time, "INFO", "literal %_ ' text"));
		rows.add(row(other, env, event, time, "ERROR", "normal"));
		rows.add(row(other, secondEnv, event, time, "CRITICAL", "normal"));
		rows.add(row(app, env, UUID.randomUUID(), time.plusMillis(1), "INFO", "edge"));
		var duplicate = new LinkedHashMap<>(rows.getFirst());
		duplicate.put("processed_at", storedTime(time.plusMillis(99)));
		var physical = new ArrayList<>(rows);
		physical.add(duplicate);
		try (var writer = new ClickHouseConnection(props); var client = new ClickHouseReadClient(props)) {
			var payload = new StringBuilder("INSERT INTO logs FORMAT JSONEachRow\n");
			for (var row : physical)
				payload.append(json.writeValueAsString(row)).append('\n');
			writer.execute(payload.toString());
			var repository = new ClickHouseAnalysisRepository(client, json);
			var search = new LogSearchService(repository, new LogCursorCodec(json), Clock.systemUTC());
			var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app, other), null, null);
			var range = new TimeRange(time.minusSeconds(1), time.plusSeconds(1));
			var actual = new ArrayList<LogView>();
			String cursor = null;
			int pages = 0;
			do {
				var page = search.search(scope,
						new SearchFilters(null, range.from(), range.to(), null, null, cursor, 1));
				actual.addAll(page.items());
				cursor = page.nextCursor();
				assertTrue(++pages <= rows.size());
			} while (cursor != null);
			var expected = new HashSet<String>();
			for (var r : rows)
				expected.add(key(r));
			assertEquals(expected, new HashSet<>(actual.stream().map(this::key).toList()));
			assertEquals(rows.size(), actual.size());
			// The broker-independent order oracle uses ClickHouse UUID ordering, never Java
			// UUID.compareTo.
			var ordered = new ArrayList<String>();
			var params = Map.of("apps", "['" + app + "','" + other + "']");
			var sql = "SELECT event_id,application_id,environment_id,level,timestamp FROM logs FINAL WHERE application_id IN {apps:Array(UUID)} ORDER BY timestamp DESC,event_id DESC,application_id DESC,environment_id DESC,level DESC"
					+ ClickHouseAnalysisRepository.SETTINGS;
			for (String line : new String(client.query(sql, params), StandardCharsets.UTF_8).lines().toList()) {
				var n = json.readTree(line);
				ordered.add(n.get("event_id").asString() + "|" + n.get("application_id").asString() + "|"
						+ n.get("environment_id").asString() + "|" + n.get("level").asString() + "|"
						+ ClickHouseAnalysisRepository.time(n.get("timestamp").asString()).toEpochMilli());
			}
			assertEquals(ordered, actual.stream().map(this::key).toList());
			var selected = new AuthorizedScope(scope.actorId(), Set.of(app), app, secondEnv);
			assertEquals(1,
					search.search(selected,
							new SearchFilters("INFO", range.from(), range.to(), "trace", "literal %_", null, 10))
							.items().size());
			assertTrue(search
					.search(selected, new SearchFilters(null, range.from(), range.to(), null, "' OR 1=1 --", null, 10))
					.items().isEmpty());
			var edge = search.search(scope,
					new SearchFilters(null, time.plusNanos(1), time.plusMillis(1).plusNanos(1), null, null, null, 10));
			assertEquals(1, edge.items().size());
			assertEquals(time.plusMillis(1), edge.items().getFirst().timestamp());
			var metrics = repository.health(scope, range);
			assertEquals(4, metrics.size());
			assertEquals(6, metrics.stream().mapToLong(HealthMetric::totalLogs).sum());
			assertEquals(1, metrics.stream().mapToLong(HealthMetric::errorLogs).sum());
			assertEquals(1, metrics.stream().mapToLong(HealthMetric::criticalLogs).sum());
			for (var m : metrics) {
				assertEquals(time.truncatedTo(ChronoUnit.HOURS), m.timestamp());
				assertEquals((m.errorLogs() + m.criticalLogs()) / (double) m.totalLogs(), m.logErrorRate());
			}
		}
	}

	private Map<String, Object> row(UUID app, UUID env, UUID event, Instant t, String level, String message) {
		var r = new LinkedHashMap<String, Object>();
		r.put("event_id", event.toString());
		r.put("application_id", app.toString());
		r.put("environment_id", env.toString());
		r.put("host_ip", "127.0.0.1");
		r.put("level", level);
		r.put("message", message);
		r.put("timestamp", storedTime(t));
		r.put("trace_id", "trace");
		r.put("metadata_json", "{\"test\":true}");
		r.put("received_at", storedTime(t));
		r.put("processed_at", storedTime(t.plusMillis(1)));
		return r;
	}

	private String storedTime(Instant t) {
		return t.toString().replace('T', ' ').replace("Z", "");
	}

	private String key(Map<String, Object> r) {
		return r.get("event_id") + "|" + r.get("application_id") + "|" + r.get("environment_id") + "|" + r.get("level")
				+ "|" + ClickHouseAnalysisRepository.time((String) r.get("timestamp")).toEpochMilli();
	}

	private String key(LogView r) {
		return r.eventId() + "|" + r.applicationId() + "|" + r.environmentId() + "|" + r.level() + "|"
				+ r.timestamp().toEpochMilli();
	}
}
