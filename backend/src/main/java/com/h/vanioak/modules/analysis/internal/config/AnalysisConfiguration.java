package com.h.vanioak.modules.analysis.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.analysis.api.AnalysisFacade;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.internal.health.HealthAnalyticsService;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseReadClient;
import com.h.vanioak.modules.analysis.internal.query.ClickHouseAnalysisRepository;
import com.h.vanioak.modules.analysis.internal.search.LogCursorCodec;
import com.h.vanioak.modules.analysis.internal.search.LogSearchService;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {
	@Bean(destroyMethod = "close")
	ClickHouseReadClient analysisReadClient(ClickHouseProperties p) {
		return new ClickHouseReadClient(p);
	}

	@Bean
	ClickHouseAnalysisRepository analysisRepository(ClickHouseReadClient client, ObjectMapper mapper) {
		return new ClickHouseAnalysisRepository(client, mapper);
	}

	@Bean
	LogCursorCodec logCursorCodec(ObjectMapper mapper) {
		return new LogCursorCodec(mapper);
	}

	@Bean
	LogSearchService logSearchService(ClickHouseAnalysisRepository repository, LogCursorCodec codec) {
		return new LogSearchService(repository, codec, java.time.Clock.systemUTC());
	}

	@Bean
	HealthAnalyticsService healthAnalyticsService(ClickHouseAnalysisRepository repository) {
		return new HealthAnalyticsService(repository);
	}

	@Bean
	AnalysisFacade analysisFacade(LogSearchService search, HealthAnalyticsService health) {
		return new AnalysisFacade() {
			public SearchPage search(AuthorizedScope scope, SearchFilters filters) {
				return search.search(scope, filters);
			}

			public HealthResult health(AuthorizedScope scope, HealthFilters filters) {
				return health.health(scope, filters);
			}
		};
	}
}
