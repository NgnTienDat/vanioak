package com.h.vanioak.modules.analysis.internal.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.any;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.LogView;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseAnalysisRepository;

class LogSearchServiceTest {
	final ClickHouseAnalysisRepository repo = mock(ClickHouseAnalysisRepository.class);
	final LogCursorCodec codec = new LogCursorCodec(new ObjectMapper());
	final Instant t = Instant.parse("2026-10-26T00:00:00Z");
	final UUID actor = UUID.randomUUID(), app = UUID.randomUUID(), other = UUID.randomUUID(), event = UUID.randomUUID();
	final AuthorizedScope scope = new AuthorizedScope(actor, Set.of(app, other), null, null);
	final Clock clock = mock(Clock.class);
	final LogSearchService service = new LogSearchService(repo, codec, clock);

	SearchFilters f(String cursor) {
		return new SearchFilters(null, null, null, null, null, cursor, 1);
	}

	LogView row(UUID a, UUID e, String level) {
		return new LogView(event, a, e, "host", level, "msg", t, "trace", Map.of(), t, t);
	}

	@Test
	void fiveKeyCollisionsTraverseWithoutDroppingLogicalRowsAndDefaultWindowDoesNotDrift() {
		when(clock.instant()).thenReturn(t.plusSeconds(60), t.plusSeconds(86400));
		var env = UUID.randomUUID();
		var rows = List.of(row(app, env, "INFO"), row(app, env, "WARN"), row(app, UUID.randomUUID(), "WARN"),
				row(other, env, "WARN"));
		when(repo.search(any(), any(), any(), any())).thenAnswer(call -> {
			var key = (LogCursorCodec.Key) call.getArgument(3);
			int start = 0;
			if (key != null) {
				while (start < rows.size()
						&& !new LogCursorCodec.Key(t.toEpochMilli(), event, rows.get(start).applicationId(),
								rows.get(start).environmentId(), rows.get(start).level()).equals(key))
					start++;
				start++;
			}
			return rows.subList(start, Math.min(rows.size(), start + 2));
		});
		var found = new ArrayList<LogView>();
		String cursor = null;
		int pages = 0;
		do {
			var page = service.search(scope, f(cursor));
			found.addAll(page.items());
			cursor = page.nextCursor();
			assertTrue(++pages <= 4);
		} while (cursor != null);
		assertEquals(rows, found);
		verify(clock, times(1)).instant();
	}

	@Test
	void invalidFiltersAndLimitsNeverQuery() {
		for (var q : List.of(new SearchFilters(null, t, null, null, null, null, 1),
				new SearchFilters("OTHER", t, t.plusSeconds(1), null, null, null, 1),
				new SearchFilters(null, t, t.plusSeconds(1), "x".repeat(1025), null, null, 1),
				new SearchFilters(null, t, t.plusSeconds(1), null, "x".repeat(4097), null, 1),
				new SearchFilters(null, t, t.plusSeconds(1), null, null, null, 0),
				new SearchFilters(null, t, t.plusSeconds(1), null, null, null, 101),
				new SearchFilters(null, t, t.plus(Duration.ofDays(8)), null, null, null, 1)))
			assertThrows(AnalysisException.class, () -> service.search(scope, q));
		verifyNoInteractions(repo);
	}

	@Test
	void halfOpenSubMillisecondConversionAndNegativeEpochAreExact() {
		var r = new TimeRange(t.plusNanos(1), t.plusNanos(1000001));
		assertEquals(t.toEpochMilli() + 1, r.fromMillis());
		assertEquals(t.toEpochMilli() + 2, r.toMillis());
		assertEquals(-999, TimeRange.ceilMillis(Instant.ofEpochSecond(-1, 500000)));
		assertThrows(AnalysisException.class, () -> new TimeRange(t, t));
	}

	@Test
	void cursorRejectsChangedFiltersCallerOrRevokedScope() {
		when(clock.instant()).thenReturn(t.plusSeconds(60));
		var range = new TimeRange(t.minusSeconds(60), t.plusSeconds(60));
		var key = new LogCursorCodec.Key(t.toEpochMilli(), event, app, UUID.randomUUID(), "INFO");
		String token = codec.encode(key, range, codec.fingerprint(scope, f(null), range));
		assertThrows(AnalysisException.class,
				() -> service.search(new AuthorizedScope(actor, Set.of(), null, null), f(token)));
		assertThrows(AnalysisException.class,
				() -> service.search(scope, new SearchFilters(null, null, null, null, "changed", token, 1)));
		verifyNoInteractions(repo);
	}

	@Test
	void emptyScopeAndEmptyResultsHaveNoNextCursor() {
		when(clock.instant()).thenReturn(t);
		assertTrue(service.search(new AuthorizedScope(actor, Set.of(), null, null), f(null)).items().isEmpty());
		verifyNoInteractions(repo);
		when(repo.search(any(), any(), any(), any())).thenReturn(List.of());
		assertNull(service.search(scope, f(null)).nextCursor());
	}

	@Test
	void extremeToOnlyBoundsFail400InsteadOfOverflowing() {
		var r = mock(ClickHouseAnalysisRepository.class);
		var service = new LogSearchService(r, new LogCursorCodec(new ObjectMapper()), Clock.systemUTC());
		var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(UUID.randomUUID()), null, null);
		assertEquals(ErrorCode.INVALID_QUERY, assertThrows(AnalysisException.class,
				() -> service.search(scope, new SearchFilters(null, null, java.time.Instant.MIN, null, null, null, 1)))
				.getErrorCode());
		verifyNoInteractions(r);
	}
}
