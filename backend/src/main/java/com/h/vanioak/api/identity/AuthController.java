package com.h.vanioak.api.identity;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.identity.dto.LoginRequest;
import com.h.vanioak.api.identity.dto.LoginResponse;
import com.h.vanioak.api.identity.dto.RefreshTokenRequest;
import com.h.vanioak.api.identity.dto.RefreshTokenResponse;
import com.h.vanioak.modules.identity.api.AuthFacade;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthFacade auth;

	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		var result = auth.login(request.username(), request.password());
		var user = result.user();
		return ApiResponse.success(
				new LoginResponse(
						result.accessToken(),
						result.refreshToken(),
						result.tokenType(),
						result.expiresIn(),
						new LoginResponse.UserSummary(user.id(), user.username(), user.role())));
	}

	@PostMapping("/refresh")
	public ApiResponse<RefreshTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		var result = auth.refresh(request.refreshToken());
		return ApiResponse.success(new RefreshTokenResponse(result.accessToken(), result.refreshToken(),
				result.tokenType(), result.expiresIn()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		auth.logout(request.refreshToken(), bearer(authorization));
		return ApiResponse.success(null);
	}

	private String bearer(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
			return null;
		String credential = authorization.substring(7).trim();
		return credential.isEmpty() ? null : credential;
	}
}
