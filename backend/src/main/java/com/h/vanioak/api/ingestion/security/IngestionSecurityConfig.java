package com.h.vanioak.api.ingestion.security;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import com.h.vanioak.modules.ingestion.api.IngestionFacade;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class IngestionSecurityConfig {
	@Bean
	public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(IngestionFacade ingestion, ObjectMapper mapper) {
		return new ApiKeyAuthenticationFilter(ingestion, mapper);
	}

	@Bean
	@Order(1)
	public SecurityFilterChain ingestionSecurityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeys)
			throws Exception {
		var paths = PathPatternRequestMatcher.withDefaults();
		return http.securityMatcher(new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/api/v1/logs"),
				paths.matcher(HttpMethod.POST, "/api/v1/logs/batch")))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(errors -> errors.authenticationEntryPoint(apiKeys).accessDeniedHandler(apiKeys))
				.authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(apiKeys, UsernamePasswordAuthenticationFilter.class).build();
	}

	@Bean
	public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
			ApiKeyAuthenticationFilter filter) {
		var registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
