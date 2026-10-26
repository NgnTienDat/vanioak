package com.h.vanioak.modules.analysis.internal.health;

import com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthResult;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseAnalysisRepository;

public class HealthAnalyticsService {
	private final ClickHouseAnalysisRepository repository;

	public HealthAnalyticsService(ClickHouseAnalysisRepository repository) {
		this.repository = repository;
	}

	public HealthResult health(AuthorizedScope scope, HealthFilters filters) {
		if (!"hour".equals(filters.bucket()))
			throw new AnalysisException(ErrorCode.INVALID_QUERY);
		var range = new TimeRange(filters.from(), filters.to());
		return new HealthResult("hour",
				scope.applicationIds().isEmpty() ? java.util.List.of() : repository.health(scope, range));
	}
}
