package com.h.vanioak.modules.analysis.internal.search;

import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchPage;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseAnalysisRepository;

public class LogSearchService {
	private final ClickHouseAnalysisRepository repository;
	private final LogCursorCodec codec;
	private final Clock clock;

	public LogSearchService(ClickHouseAnalysisRepository repository, LogCursorCodec codec, Clock clock) {
		this.repository = repository;
		this.codec = codec;
		this.clock = clock;
	}

	public SearchPage search(AuthorizedScope scope, SearchFilters f) {
		if (f.limit() < 1 || f.limit() > 100 || f.from() != null && f.to() == null
				|| f.level() != null && !List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(f.level())
				|| f.traceId() != null && f.traceId().getBytes(StandardCharsets.UTF_8).length > 1024
				|| f.message() != null && f.message().getBytes(StandardCharsets.UTF_8).length > 4096)
			throw new AnalysisException(ErrorCode.INVALID_QUERY);
		var cursor = f.cursor() == null ? null : codec.decode(f.cursor());
		TimeRange range;
		try {
			if (f.to() != null)
				range = new TimeRange(f.from() == null ? f.to().minus(Duration.ofDays(7)) : f.from(), f.to());
			else if (cursor != null)
				range = cursor.range();
			else {
				var to = clock.instant();
				range = new TimeRange(to.minus(Duration.ofDays(7)), to);
			}
		} catch (java.time.DateTimeException | ArithmeticException ex) {
			throw new AnalysisException(ErrorCode.INVALID_QUERY);
		}
		String context = codec.fingerprint(scope, f, range);
		if (cursor != null && (!context.equals(cursor.context()) || !range.equals(cursor.range())
				|| !scope.permits(cursor.key().applicationId(), cursor.key().environmentId())
				|| f.level() != null && !f.level().equals(cursor.key().level())))
			throw new AnalysisException(ErrorCode.INVALID_CURSOR);
		if (scope.applicationIds().isEmpty())
			return new SearchPage(List.of(), null);
		var rows = repository.search(scope, f, range, cursor == null ? null : cursor.key());
		var items = rows.stream().limit(f.limit()).toList();
		String next = null;
		if (rows.size() > f.limit()) {
			var last = items.getLast();
			next = codec.encode(new LogCursorCodec.Key(last.timestamp().toEpochMilli(), last.eventId(),
					last.applicationId(), last.environmentId(), last.level()), range, context);
		}
		return new SearchPage(items, next);
	}
}
