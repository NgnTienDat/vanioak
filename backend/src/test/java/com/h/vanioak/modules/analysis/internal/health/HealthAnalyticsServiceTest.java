package com.h.vanioak.modules.analysis.internal.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthFilters;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseAnalysisRepository;

class HealthAnalyticsServiceTest {
	final ClickHouseAnalysisRepository repository = mock(ClickHouseAnalysisRepository.class);
	final HealthAnalyticsService service = new HealthAnalyticsService(repository);
	final UUID app = UUID.randomUUID();
	final AuthorizedScope scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app), null, null);
	final Instant start = Instant.parse("2026-10-26T00:30:00Z");

	@Test
	void preservesExactPartialHourRangeAndEmptyDataset() {
		when(repository.health(any(), any())).thenReturn(List.of());
		var result = service.health(scope, new HealthFilters(start, start.plusSeconds(3600), "hour"));
		assertEquals("hour", result.bucket());
		assertTrue(result.items().isEmpty());
		verify(repository).health(eq(scope), eq(new TimeRange(start, start.plusSeconds(3600))));
	}

	@Test
	void rejectsMissingReversedWideRangesAndOtherBuckets() {
		for (var f : List.of(new HealthFilters(null, start, "hour"), new HealthFilters(start, start, "hour"),
				new HealthFilters(start, start.plusSeconds(604801), "hour"),
				new HealthFilters(start, start.plusSeconds(1), "day"))) {
			assertThrows(AnalysisException.class, () -> service.health(scope, f));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void noAssignmentsReturnsEmptyWithoutQuery() {
		assertTrue(service.health(new AuthorizedScope(UUID.randomUUID(), Set.of(), null, null),
				new HealthFilters(start, start.plusSeconds(1), "hour")).items().isEmpty());
		verifyNoInteractions(repository);
	}
}
