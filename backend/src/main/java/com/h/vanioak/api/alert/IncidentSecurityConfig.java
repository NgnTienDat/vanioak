package com.h.vanioak.api.alert;

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
import com.h.vanioak.api.identity.security.JwtAuthenticationFilter;
import com.h.vanioak.api.identity.security.SecurityErrorHandler;

@Configuration(proxyBeanMethods=false)
public class IncidentSecurityConfig {
	@Bean @Order(5)
	SecurityFilterChain incidentSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt, SecurityErrorHandler errors) throws Exception {
		var paths=PathPatternRequestMatcher.withDefaults();
		return http.securityMatcher(new OrRequestMatcher(paths.matcher(HttpMethod.GET,"/api/v1/incidents"),paths.matcher(HttpMethod.GET,"/api/v1/incidents/{incidentId}"),
				paths.matcher(HttpMethod.PATCH,"/api/v1/incidents/{incidentId}/status")))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.authorizeHttpRequests(r -> r.anyRequest().hasAnyRole("ADMIN","ENGINEER"))
				.addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();
	}
}
