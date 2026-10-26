package com.h.vanioak.api.analysis;

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
import com.h.vanioak.api.identity.security.JwtAuthenticationFilter;
import com.h.vanioak.api.identity.security.SecurityErrorHandler;

@Configuration(proxyBeanMethods = false)
public class AnalysisSecurityConfig {
	@Bean
	@Order(2)
	SecurityFilterChain analysisSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
			SecurityErrorHandler errors) throws Exception {
		var paths = PathPatternRequestMatcher.withDefaults();
		return http
				.securityMatcher(new org.springframework.security.web.util.matcher.OrRequestMatcher(
						paths.matcher(HttpMethod.GET, "/api/v1/logs"),
						paths.matcher(HttpMethod.GET, "/api/v1/analytics/health")))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.authorizeHttpRequests(r -> r.anyRequest().hasAnyRole("ADMIN", "ENGINEER"))
				.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build();
	}
}
