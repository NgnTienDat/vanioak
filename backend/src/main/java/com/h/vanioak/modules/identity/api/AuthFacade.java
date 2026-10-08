package com.h.vanioak.modules.identity.api;

import java.util.UUID;

public interface AuthFacade {

	AuthenticatedUser authenticateAccessToken(String accessToken);

	LoginResult login(String username, String password);

	LoginResult refresh(String refreshToken);

	void logout(String refreshToken, String accessToken);

	record LoginResult(String accessToken, String refreshToken, String tokenType, long expiresIn, UserSummary user) { }
	record UserSummary(UUID id, String username, String role) { }
	record AuthenticatedUser(UUID id, String role) { }
}
