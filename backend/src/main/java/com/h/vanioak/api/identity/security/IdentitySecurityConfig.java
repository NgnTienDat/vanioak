package com.h.vanioak.api.identity.security;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.h.vanioak.modules.identity.api.AuthFacade;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
public class IdentitySecurityConfig {

	@Bean
	public SecurityErrorHandler securityErrorHandler(ObjectMapper mapper) {
		return new SecurityErrorHandler(mapper);
	}

	@Bean
	public JwtAuthenticationFilter jwtAuthenticationFilter(AuthFacade auth, SecurityErrorHandler errors,
			@Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled,
			@Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUiEnabled) {
		var paths = PathPatternRequestMatcher.withDefaults();
		List<RequestMatcher> publicRequests = new ArrayList<>();
		for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")) {
			publicRequests.add(paths.matcher(HttpMethod.POST, path));
		}
		if (apiDocsEnabled) {
			for (String path : List.of("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml")) {
				publicRequests.add(paths.matcher(path));
			}
		}
		if (swaggerUiEnabled) {
			for (String path : List.of("/swagger-ui.html", "/swagger-ui/**")) {
				publicRequests.add(paths.matcher(path));
			}
		}
		return new JwtAuthenticationFilter(auth, errors, new OrRequestMatcher(publicRequests));
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
			SecurityErrorHandler errors) throws Exception {
		return http
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
				.authorizeHttpRequests(requests -> requests
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(jwt::shouldNotFilter).permitAll()
						.requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.GET, "/api/v1/applications").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/v1/applications").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PATCH, "/api/v1/applications/{applicationId}").hasRole("ADMIN")
						.requestMatchers(HttpMethod.GET, "/api/v1/applications/{applicationId}/environments/{environmentId}/api-keys").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/v1/applications/{applicationId}/environments/{environmentId}/api-keys").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/v1/api-keys/{credentialId}/rotate").hasRole("ADMIN")
						.requestMatchers(HttpMethod.DELETE, "/api/v1/api-keys/{credentialId}").hasRole("ADMIN")
						.anyRequest().denyAll())
				.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
				.build();
	}

	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter jwt) {
		var registration = new FilterRegistrationBean<>(jwt);
		registration.setEnabled(false);
		return registration;
	}
}
