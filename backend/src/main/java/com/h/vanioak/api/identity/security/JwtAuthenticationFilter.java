package com.h.vanioak.api.identity.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.h.vanioak.modules.identity.api.AuthFacade;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final AuthFacade auth;
	private final SecurityErrorHandler errors;
	private final RequestMatcher bypass;

	public JwtAuthenticationFilter(AuthFacade auth, SecurityErrorHandler errors, RequestMatcher bypass) {
		this.auth = auth;
		this.errors = errors;
		this.bypass = bypass;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return bypass.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String token = header.substring(7).trim();
			if (!token.isEmpty()) {
				AuthFacade.AuthenticatedUser user;
				try {
					user = auth.authenticateAccessToken(token);
				} catch (RuntimeException exception) {
					SecurityContextHolder.clearContext();
					errors.authenticationFailure(response, exception);
					return;
				}
				var context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.id().toString(), null,
						List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
				SecurityContextHolder.setContext(context);
			}
		}
		chain.doFilter(request, response);
	}
}
