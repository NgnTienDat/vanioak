package com.h.vanioak.common.config.clickhouse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseConfiguration {

	@Bean(destroyMethod = "close")
	ClickHouseConnection clickHouseConnection(ClickHouseProperties properties) {
		return new ClickHouseConnection(properties);
	}
}
