package com.h.vanioak.modules.identity.internal.auth;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

	private final SecretKey accessSigningKey;
	private final SecretKey refreshSigningKey;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;

	public JwtService(
			@Value("${security.jwt.access-secret}") String accessSecret,
			@Value("${security.jwt.refresh-secret}") String refreshSecret,
			@Value("${security.jwt.access-token-ttl}") Duration accessTokenTtl,
			@Value("${security.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
		this.accessSigningKey = signingKey(accessSecret);
		this.refreshSigningKey = signingKey(refreshSecret);
		if (Arrays.equals(accessSigningKey.getEncoded(), refreshSigningKey.getEncoded())) {
			throw new IllegalArgumentException("Access and refresh signing keys must differ");
		}
		this.accessTokenTtl = validateTtl(accessTokenTtl);
		this.refreshTokenTtl = validateTtl(refreshTokenTtl);
	}

	public String generateAccessToken(UUID userId) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return Jwts.builder()
				.subject(Objects.requireNonNull(userId, "User ID is required").toString())
				.id(UUID.randomUUID().toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(accessTokenTtl)))
				.claim("token_use", "access")
				.signWith(accessSigningKey, Jwts.SIG.HS256)
				.compact();
	}

	public String generateRefreshToken(UUID userId, UUID tokenId, UUID familyId) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return Jwts.builder()
				.subject(Objects.requireNonNull(userId, "User ID is required").toString())
				.id(Objects.requireNonNull(tokenId, "Token ID is required").toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(refreshTokenTtl)))
				.claim("token_use", "refresh")
				.claim("family_id", Objects.requireNonNull(familyId, "Family ID is required").toString())
				.signWith(refreshSigningKey, Jwts.SIG.HS256)
				.compact();
	}

	public Claims parseAccessToken(String token) {
		return parse(token, accessSigningKey, "access");
	}

	public Claims parseRefreshToken(String token) {
		Claims claims = parse(token, refreshSigningKey, "refresh");
		validateUuid(claims.get("family_id", String.class));
		return claims;
	}

	private Claims parse(String token, SecretKey key, String tokenUse) {
		Claims claims = Jwts.parser()
				.verifyWith(key)
				.sig().clear().add(Jwts.SIG.HS256).and()
				.require("token_use", tokenUse)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		validateUuid(claims.getSubject());
		validateUuid(claims.getId());
		Date issuedAt = claims.getIssuedAt();
		Date expiresAt = claims.getExpiration();
		if (issuedAt == null || expiresAt == null || !expiresAt.after(issuedAt)) {
			throw new JwtException("JWT issued-at and expiration must define a positive lifetime");
		}
		return claims;
	}

	private void validateUuid(String value) {
		if (value == null) throw new JwtException("Required UUID claim is missing");
		try {
			UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new JwtException("Invalid UUID claim");
		}
	}

	private SecretKey signingKey(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("JWT signing key is required");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(secret);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("JWT signing key must be valid Base64");
		}
		if (bytes.length < 32) {
			throw new IllegalArgumentException("JWT signing key must contain at least 32 bytes");
		}
		return Keys.hmacShaKeyFor(bytes);
	}

	private Duration validateTtl(Duration ttl) {
		if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.getNano() != 0) {
			throw new IllegalArgumentException("JWT TTL must be a positive whole-second duration");
		}
		try {
			Date.from(Instant.now().plus(ttl));
		} catch (DateTimeException | ArithmeticException | IllegalArgumentException exception) {
			throw new IllegalArgumentException("JWT TTL exceeds the supported date range");
		}
		return ttl;
	}
}
