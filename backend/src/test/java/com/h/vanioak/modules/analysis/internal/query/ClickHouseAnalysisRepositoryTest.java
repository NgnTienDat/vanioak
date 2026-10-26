package com.h.vanioak.modules.analysis.internal.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.argThat;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.internal.search.LogCursorCodec.Key;

class ClickHouseAnalysisRepositoryTest {
	@Test
	void sqlUsesFinalTypedCompositeAndEveryFilterBeforeLimit() {
		var client = mock(ClickHouseReadClient.class);
		when(client.query(anyString(), anyMap())).thenReturn(new byte[0]);
		var repo = new ClickHouseAnalysisRepository(client, new ObjectMapper());
		var app = UUID.randomUUID();
		var env = UUID.randomUUID();
		var t = Instant.parse("2026-10-26T00:00:00Z");
		String text = "' OR 1=1 -- %_";
		repo.search(new AuthorizedScope(UUID.randomUUID(), Set.of(app), app, env),
				new SearchFilters("INFO", t, t.plusSeconds(2), text, text, null, 1), new TimeRange(t, t.plusSeconds(2)),
				new Key(t.plusMillis(1).toEpochMilli(), UUID.randomUUID(), app, env, "INFO"));
		var sql = ArgumentCaptor.forClass(String.class);
		var params = ArgumentCaptor.forClass(Map.class);
		verify(client).query(sql.capture(), params.capture());
		String query = sql.getValue();
		assertTrue(query.contains("FROM logs FINAL"));
		assertTrue(query.contains("(timestamp,event_id,application_id,environment_id,level)<"));
		assertTrue(query
				.contains("ORDER BY timestamp DESC,event_id DESC,application_id DESC,environment_id DESC,level DESC"));
		assertFalse(query.contains(text));
		assertEquals(text, params.getValue().get("message"));
		assertEquals("2", params.getValue().get("fetch"));
		assertTrue(query.indexOf("position(") < query.indexOf(" LIMIT "));
	}

	@Test
	void rejectsTruncatedMalformedOrOutOfScopeRowsWithoutPartialSuccess() {
		var client = mock(ClickHouseReadClient.class);
		var repo = new ClickHouseAnalysisRepository(client, new ObjectMapper());
		var app = UUID.randomUUID();
		var t = Instant.parse("2026-10-26T00:00:00Z");
		var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app), null, null);
		var f = new SearchFilters(null, t, t.plusSeconds(1), null, null, null, 1);
		for (String body : List.of("{}", "not-json\n", "{} {}\n")) {
			when(client.query(anyString(), anyMap())).thenReturn(body.getBytes());
			assertEquals(ErrorCode.UNAVAILABLE, assertThrows(AnalysisException.class,
					() -> repo.search(scope, f, new TimeRange(t, t.plusSeconds(1)), null)).getErrorCode());
		}
	}

	@Test
	void parsesActualProjectionAndRejectsOutOfScopeAndInvalidUtf8() {
		var client = mock(ClickHouseReadClient.class);
		var mapper = new ObjectMapper();
		var repo = new ClickHouseAnalysisRepository(client, mapper);
		var app = UUID.randomUUID();
		var env = UUID.randomUUID();
		var event = UUID.randomUUID();
		var t = Instant.parse("2026-10-26T00:00:00Z");
		var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app), app, env);
		var f = new SearchFilters(null, t, t.plusSeconds(1), null, null, null, 1);
		var range = new TimeRange(t, t.plusSeconds(1));
		var row = new LinkedHashMap<String, Object>();
		row.put("event_id", event.toString());
		row.put("application_id", app.toString());
		row.put("environment_id", env.toString());
		row.put("host_ip", "host");
		row.put("level", "INFO");
		row.put("message", "Unicode log");
		row.put("timestamp", "2026-10-26 00:00:00.000");
		row.put("trace_id", "trace");
		row.put("metadata_json", "{\"nullable\":null}");
		row.put("received_at", t.toString());
		row.put("processed_at", t.toString());
		when(client.query(anyString(), anyMap())).thenReturn((mapper.writeValueAsString(row) + "\n").replace("\n", "\n")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var actual = repo.search(scope, f, range, null);
		assertEquals(1, actual.size());
		assertEquals(event, actual.getFirst().eventId());
		assertTrue(actual.getFirst().metadata().containsKey("nullable"));
		row.put("application_id", UUID.randomUUID().toString());
		when(client.query(anyString(), anyMap()))
				.thenReturn((mapper.writeValueAsString(row) + "\n").replace("\n", "\n").getBytes());
		assertThrows(AnalysisException.class, () -> repo.search(scope, f, range, null));
		when(client.query(anyString(), anyMap())).thenReturn(new byte[] { (byte) 0xc3, (byte) 0x28, 10 });
		assertThrows(AnalysisException.class, () -> repo.search(scope, f, range, null));
	}

	@Test
	void hourlyQueryUsesLogicalRowsAndExactScopeWithLongFractionCounts() {
		var client = mock(ClickHouseReadClient.class);
		var mapper = new ObjectMapper();
		var repo = new ClickHouseAnalysisRepository(client, mapper);
		var app = UUID.randomUUID();
		var env = UUID.randomUUID();
		var from = Instant.parse("2026-10-26T00:30:00Z");
		var row = new LinkedHashMap<String, Object>();
		row.put("application_id", app.toString());
		row.put("environment_id", env.toString());
		row.put("hour_start", "2026-10-26 00:00:00");
		row.put("total_logs", "4000000000");
		row.put("error_logs", "1000000000");
		row.put("critical_logs", 1000000000L);
		when(client.query(anyString(), anyMap())).thenReturn((mapper.writeValueAsString(row) + "\n").getBytes());
		var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app), app, env);
		var range = new TimeRange(from, from.plusSeconds(3600));
		var results = repo.health(scope, range);
		assertEquals(4000000000L, results.getFirst().totalLogs());
		assertEquals(.5, results.getFirst().logErrorRate());
		assertEquals(from.minusSeconds(1800), results.getFirst().timestamp());
		verify(client).query(
				argThat(sql -> sql.contains("FROM logs FINAL WHERE") && sql.contains("toStartOfHour(timestamp,'UTC')")
						&& sql.contains("GROUP BY application_id,environment_id,hour_start")),
				argThat(p -> p.get("env").equals(env.toString())
						&& p.get("from_ms").equals(Long.toString(from.toEpochMilli()))));
		for (Object bad : List.of("0", "-1", "9223372036854775808", "1.0")) {
			row.put("total_logs", bad);
			when(client.query(anyString(), anyMap())).thenReturn((mapper.writeValueAsString(row) + "\n").getBytes());
			assertThrows(AnalysisException.class, () -> repo.health(scope, range));
		}
	}
}
