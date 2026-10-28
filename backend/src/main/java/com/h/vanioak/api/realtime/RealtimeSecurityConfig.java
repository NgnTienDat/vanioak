package com.h.vanioak.api.realtime;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.identity.security.JwtAuthenticationFilter;
import com.h.vanioak.api.identity.security.SecurityErrorHandler;
import com.h.vanioak.modules.realtime.internal.config.RealtimeProperties;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class RealtimeSecurityConfig {
	@Bean
	@Order(3)
	SecurityFilterChain realtimeSecurityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
			SecurityErrorHandler errors, RealtimeProperties properties, ObjectMapper mapper) throws Exception {
		var paths = PathPatternRequestMatcher.withDefaults();
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Accept", "Last-Event-ID", "Cache-Control"));
		cors.setAllowCredentials(false);
		CorsFilter filter = new CorsFilter(request -> RealtimeController.PATH.equals(request.getRequestURI()) ? cors : null);
		filter.setCorsProcessor(new DefaultCorsProcessor() {
			@Override
			protected void rejectRequest(ServerHttpResponse response) throws IOException {
				response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				mapper.writeValue(response.getBody(), ApiResponse.error("Access denied"));
			}
		});
		return http.securityMatcher(new OrRequestMatcher(paths.matcher(HttpMethod.GET, RealtimeController.PATH),
				paths.matcher(HttpMethod.OPTIONS, RealtimeController.PATH)))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.authorizeHttpRequests(r -> r.requestMatchers(HttpMethod.OPTIONS, RealtimeController.PATH).permitAll()
						.anyRequest().hasAnyRole("ADMIN", "ENGINEER"))
				.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(filter, JwtAuthenticationFilter.class).build();
	}
}
