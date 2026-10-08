package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.h.vanioak.common.security.SecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.internal.auth.AuthService;
import com.h.vanioak.modules.identity.internal.auth.AccessTokenBlacklist;
import com.h.vanioak.modules.identity.internal.auth.JwtService;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;

@DataJpaTest(showSql = false, properties = {
		"spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, JwtService.class, SecurityConfig.class })
class AuthLoginIT {

	@MockitoBean
	private AccessTokenBlacklist accessTokenBlacklist;

	@Autowired
	private AuthFacade auth;
	@Autowired
	private JwtService jwt;
	@Autowired
	private UserRepository users;
	@Autowired
	private RefreshTokenRepository refreshTokens;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private EntityManager entityManager;

	@Test
	void savesMatchingRefreshMetadataForIndependentLoginSessions() {
		String username = "auth-login-it-" + UUID.randomUUID();
		String password = "test-only-login-password";
		UserEntity user = users.saveAndFlush(new UserEntity(username, encoder.encode(password)));
		UUID userId = user.getId();
		entityManager.clear();
		assertTrue(AopUtils.isAopProxy(auth));

		var first = auth.login(username, password);
		var second = auth.login(username, password);
		entityManager.clear();
		var firstClaims = jwt.parseRefreshToken(first.refreshToken());
		var secondClaims = jwt.parseRefreshToken(second.refreshToken());
		assertNotEquals(UUID.fromString(firstClaims.getId()), UUID.fromString(secondClaims.getId()));
		assertNotEquals(UUID.fromString(firstClaims.get("family_id", String.class)),
				UUID.fromString(secondClaims.get("family_id", String.class)));

		for (var result : new AuthFacade.LoginResult[] { first, second }) {
			var claims = jwt.parseRefreshToken(result.refreshToken());
			var stored = refreshTokens.findById(UUID.fromString(claims.getId())).orElseThrow();
			assertEquals(userId, stored.getUserId());
			assertEquals(userId, UUID.fromString(claims.getSubject()));
			assertEquals(UUID.fromString(claims.get("family_id", String.class)), stored.getFamilyId());
			assertEquals(claims.getExpiration().toInstant(), stored.getExpiresAt());
			assertNull(stored.getParentTokenId());
			assertFalse(stored.isUsed());
			assertFalse(stored.isRevoked());
			assertNotNull(stored.getCreatedAt());
			assertTrue(stored.getExpiresAt().isAfter(stored.getCreatedAt()));
			assertEquals(new AuthFacade.UserSummary(userId, username, "ENGINEER"), result.user());
			assertEquals("Bearer", result.tokenType());
			assertEquals(900, result.expiresIn());
			assertEquals(userId, UUID.fromString(jwt.parseAccessToken(result.accessToken()).getSubject()));
		}
	}
}
