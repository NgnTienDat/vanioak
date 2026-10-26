package com.h.vanioak.modules.processing.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.modules.processing.internal.persistence.ClickHouseLogWriter;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ProcessingConfiguration {

	@Bean
	ClickHouseLogWriter clickHouseLogWriter(ClickHouseConnection connection, ObjectMapper mapper) {
		return new ClickHouseLogWriter(connection, mapper);
	}

	@Bean(destroyMethod = "close")
	ProcessingBatchWriter processingBatchWriter(ClickHouseLogWriter writer,
			@Value("${vanioak.processing.batch-size}") int batchSize,
			@Value("${vanioak.processing.batch-timeout-ms}") long timeoutMillis) {
		return new ProcessingBatchWriter(writer, batchSize, timeoutMillis);
	}
}
