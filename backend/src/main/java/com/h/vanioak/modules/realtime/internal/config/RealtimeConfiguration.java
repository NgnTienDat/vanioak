package com.h.vanioak.modules.realtime.internal.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeReadiness;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfiguration {
	@Bean
	RealtimeService realtimeFacade(RealtimeProperties properties, ObjectMapper mapper, RealtimeReadiness readiness) {
		return new RealtimeService(properties.enabled(), readiness::ready, Clock.systemUTC(), mapper);
	}
	@Bean
	RealtimeReadiness realtimeReadiness() { return new RealtimeReadiness(); }
}
