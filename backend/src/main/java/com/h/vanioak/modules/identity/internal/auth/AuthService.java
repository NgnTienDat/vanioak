package com.h.vanioak.modules.identity.internal.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService implements AuthFacade {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwt;
	private final RefreshTokenRepository refreshTokens;
	private final PlatformTransactionManager transactionManager;
	private final AccessTokenBlacklist accessTokenBlacklist;

	@Override
	public AuthenticatedUser authenticateAccessToken(String accessToken) {
		UUID userId;
		UUID jti;
		try {
			var claims = jwt.parseAccessToken(accessToken);
			userId = UUID.fromString(claims.getSubject());
			jti = UUID.fromString(claims.getId());
		} catch (JwtException | IllegalArgumentException exception) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		if (accessTokenBlacklist.isBlacklisted(jti)) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		UserEntity user = users.findById(userId)
				.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		if (user.getStatus() != Status.ACTIVE) throw new IdentityException(ErrorCode.ACCESS_DENIED);
		return new AuthenticatedUser(user.getId(), user.getRole());
	}

	@Override
	@Transactional
	public LoginResult login(String username, String password) {
		if (username == null || password == null || username.length() > 100) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
		UserEntity user = users.findByUsername(username)
				.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		if (user.getStatus() != Status.ACTIVE || !passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		return issueTokens(user, null, UUID.randomUUID());
	}

	@Override
	public LoginResult refresh(String refreshToken) {
		UUID tokenId;
		UUID familyId;
		UUID userId;
		Instant expiry;
		try {
			var claims = jwt.parseRefreshToken(refreshToken);
			tokenId = UUID.fromString(claims.getId());
			familyId = UUID.fromString(claims.get("family_id", String.class));
			userId = UUID.fromString(claims.getSubject());
			expiry = claims.getExpiration().toInstant();
		} catch (JwtException | IllegalArgumentException exception) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		var transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		LoginResult result = transaction.execute(status -> {
			// Serialize ancestor replay with descendant rotation before locking the current token.
			refreshTokens.findFamilyRootForUpdate(familyId)
					.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
			RefreshTokenEntity current = refreshTokens.findByTokenIdForUpdate(tokenId)
					.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
			if (!current.getUserId().equals(userId) || !current.getFamilyId().equals(familyId)
					|| !current.getExpiresAt().equals(expiry) || !expiry.isAfter(Instant.now())) {
				throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
			}
			if (current.isUsed() || current.isRevoked()) {
				refreshTokens.revokeFamily(familyId);
				return null;
			}
			UserEntity user = users.findById(userId)
					.filter(found -> found.getStatus() == Status.ACTIVE)
					.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
			current.setUsed(true);
			return issueTokens(user, tokenId, familyId);
		});
		// Replay is rejected only after the family revocation transaction has committed.
		if (result == null) throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		return result;
	}

	@Override
	public void logout(String refreshToken, String accessToken) {
		UUID tokenId;
		UUID familyId;
		UUID userId;
		Instant expiry;
		try {
			var claims = jwt.parseRefreshToken(refreshToken);
			tokenId = UUID.fromString(claims.getId());
			familyId = UUID.fromString(claims.get("family_id", String.class));
			userId = UUID.fromString(claims.getSubject());
			expiry = claims.getExpiration().toInstant();
		} catch (JwtException | IllegalArgumentException exception) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		Claims access = optionalAccessClaims(accessToken);
		if (access != null && !UUID.fromString(access.getSubject()).equals(userId)) {
			throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		}
		var transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.executeWithoutResult(status -> {
			refreshTokens.findFamilyRootForUpdate(familyId)
					.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
			RefreshTokenEntity current = refreshTokens.findByTokenIdForUpdate(tokenId)
					.orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
			if (!current.getUserId().equals(userId) || !current.getFamilyId().equals(familyId)
					|| !current.getExpiresAt().equals(expiry) || !expiry.isAfter(Instant.now())
					|| current.isUsed() || current.isRevoked()) {
				throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
			}
			if (access != null) {
				accessTokenBlacklist.blacklist(UUID.fromString(access.getId()), access.getExpiration().toInstant());
			}
			refreshTokens.revokeFamily(familyId);
		});
	}

	private Claims optionalAccessClaims(String accessToken) {
		try {
			return jwt.parseAccessToken(accessToken);
		} catch (JwtException | IllegalArgumentException exception) {
			return null;
		}
	}

	private LoginResult issueTokens(UserEntity user, UUID parentTokenId, UUID familyId) {
		UUID tokenId = UUID.randomUUID();
		String accessToken = jwt.generateAccessToken(user.getId());
		String refreshToken = jwt.generateRefreshToken(user.getId(), tokenId, familyId);
		Instant refreshExpiry = jwt.parseRefreshToken(refreshToken).getExpiration().toInstant();
		var accessClaims = jwt.parseAccessToken(accessToken);
		long expiresIn = Duration.between(accessClaims.getIssuedAt().toInstant(),
				accessClaims.getExpiration().toInstant()).getSeconds();
		refreshTokens.saveAndFlush(new RefreshTokenEntity(tokenId, familyId, parentTokenId, user.getId(), refreshExpiry));
		return new LoginResult(accessToken, refreshToken, "Bearer", expiresIn,
				new UserSummary(user.getId(), user.getUsername(), user.getRole()));
	}
}
