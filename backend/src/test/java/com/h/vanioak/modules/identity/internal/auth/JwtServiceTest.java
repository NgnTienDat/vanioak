package com.h.vanioak.modules.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;

@SpringBootTest(classes = JwtService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JwtServiceTest {

	private static final UUID USER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
	private static final UUID TOKEN_ID = UUID.fromString("23456789-1234-1234-1234-123456789abc");
	private static final UUID FAMILY_ID = UUID.fromString("34567890-1234-1234-1234-123456789abc");

	@Autowired
	private JwtService service;
	@Value("${security.jwt.access-secret}")
	private String accessSecret;
	@Value("${security.jwt.refresh-secret}")
	private String refreshSecret;

	@Test
	void accessRoundTripUsesOnlyContractClaimsAndHs256() {
		String token = service.generateAccessToken(USER_ID);
		Claims claims = service.parseAccessToken(token);
		assertEquals(USER_ID, UUID.fromString(claims.getSubject()));
		assertNotNull(UUID.fromString(claims.getId()));
		assertNotEquals(claims.getId(), service.parseAccessToken(service.generateAccessToken(USER_ID)).getId());
		assertEquals("access", claims.get("token_use"));
		assertEquals(Set.of("sub", "jti", "iat", "exp", "token_use"), claims.keySet());
		assertEquals(Duration.ofMinutes(15), lifetime(claims));
		assertEquals(0, claims.getIssuedAt().toInstant().getNano());
		assertEquals("HS256", Jwts.parser().verifyWith(key(accessSecret)).build()
				.parseSignedClaims(token).getHeader().getAlgorithm());
		assertThrows(JwtException.class, () -> Jwts.parser().verifyWith(key(refreshSecret)).build()
				.parseSignedClaims(token));
	}

	@Test
	void refreshRoundTripPreservesTokenAndFamilyIdentity() {
		String token = service.generateRefreshToken(USER_ID, TOKEN_ID, FAMILY_ID);
		Claims claims = service.parseRefreshToken(token);
		assertEquals(USER_ID, UUID.fromString(claims.getSubject()));
		assertEquals(TOKEN_ID, UUID.fromString(claims.getId()));
		assertEquals(FAMILY_ID, UUID.fromString(claims.get("family_id", String.class)));
		assertEquals("refresh", claims.get("token_use"));
		assertEquals(Set.of("sub", "jti", "iat", "exp", "token_use", "family_id"), claims.keySet());
		assertEquals(Duration.ofDays(7), lifetime(claims));
		assertEquals("HS256", Jwts.parser().verifyWith(key(refreshSecret)).build()
				.parseSignedClaims(token).getHeader().getAlgorithm());
		assertThrows(JwtException.class, () -> Jwts.parser().verifyWith(key(accessSecret)).build()
				.parseSignedClaims(token));
	}

	@Test
	void rejectsExpiredTokensForBothParsers() {
		for (String type : new String[] { "access", "refresh" }) {
			Map<String, Object> claims = claims(type);
			claims.put("iat", Date.from(Instant.now().minusSeconds(120)));
			claims.put("exp", Date.from(Instant.now().minusSeconds(60)));
			String token = sign(claims, type);
			assertThrows(ExpiredJwtException.class, () -> parse(token, type));
		}
	}

	@Test
	void rejectsWrongKeyAndWrongTypeIncludingCorrectlySignedWrongType() {
		assertThrows(JwtException.class, () -> service.parseAccessToken(
				service.generateRefreshToken(USER_ID, TOKEN_ID, FAMILY_ID)));
		assertThrows(JwtException.class, () -> service.parseRefreshToken(service.generateAccessToken(USER_ID)));
		for (String type : new String[] { "access", "refresh" }) {
			Map<String, Object> claims = claims(type);
			claims.put("token_use", type.equals("access") ? "refresh" : "access");
			String token = sign(claims, type);
			assertThrows(JwtException.class, () -> parse(token, type));
			String wrongKey = sign(claims(type), type.equals("access") ? "refresh" : "access");
			assertThrows(JwtException.class, () -> parse(wrongKey, type));
		}
	}

	@Test
	void rejectsAlgorithmsOtherThanHs256EvenWithCorrectKey() {
		String strongAccess = Base64.getEncoder().encodeToString(new byte[64]);
		byte[] otherBytes = new byte[64];
		otherBytes[0] = 1;
		String strongRefresh = Base64.getEncoder().encodeToString(otherBytes);
		JwtService strongKeys = new JwtService(strongAccess, strongRefresh, Duration.ofMinutes(15), Duration.ofDays(7));
		for (String type : new String[] { "access", "refresh" }) {
			SecretKey signingKey = key(type.equals("access") ? strongAccess : strongRefresh);
			for (var algorithm : new MacAlgorithm[] { Jwts.SIG.HS384, Jwts.SIG.HS512 }) {
				String token = Jwts.builder().claims(claims(type)).signWith(signingKey, algorithm).compact();
				assertThrows(JwtException.class, () -> {
					if (type.equals("access")) strongKeys.parseAccessToken(token);
					else strongKeys.parseRefreshToken(token);
				});
			}
		}
	}

	@Test
	void rejectsMalformedAndUnsignedTokens() {
		for (String type : new String[] { "access", "refresh" }) {
			assertThrows(JwtException.class, () -> parse("not.a.jwt", type));
			assertThrows(IllegalArgumentException.class, () -> parse("", type));
			assertThrows(IllegalArgumentException.class, () -> parse(null, type));
			String unsigned = Jwts.builder().claims(claims(type)).compact();
			assertThrows(JwtException.class, () -> parse(unsigned, type));
		}
	}

	@Test
	void rejectsMissingRequiredClaimsAndInvalidUuidClaims() {
		for (String type : new String[] { "access", "refresh" }) {
			for (String name : claims(type).keySet()) {
				Map<String, Object> missing = claims(type);
				missing.remove(name);
				String token = sign(missing, type);
				assertThrows(JwtException.class, () -> parse(token, type));
			}
			String[] uuidClaims = type.equals("access") ? new String[] { "sub", "jti" }
					: new String[] { "sub", "jti", "family_id" };
			for (String name : uuidClaims) {
				Map<String, Object> invalid = claims(type);
				invalid.put(name, "invalid-uuid");
				String token = sign(invalid, type);
				assertThrows(JwtException.class, () -> parse(token, type));
			}
		}
	}

	@Test
	void acceptsValidUuidWithoutCanonicalStringRestriction() {
		Map<String, Object> claims = claims("refresh");
		claims.put("sub", USER_ID.toString().toUpperCase());
		claims.put("jti", TOKEN_ID.toString().toUpperCase());
		claims.put("family_id", FAMILY_ID.toString().toUpperCase());
		Claims parsed = service.parseRefreshToken(sign(claims, "refresh"));
		assertEquals(USER_ID, UUID.fromString(parsed.getSubject()));
		assertEquals(TOKEN_ID, UUID.fromString(parsed.getId()));
		assertEquals(FAMILY_ID, UUID.fromString(parsed.get("family_id", String.class)));
	}

	@Test
	void rejectsWrongClaimTypesAndInvalidTimeOrdering() {
		for (String type : new String[] { "access", "refresh" }) {
			for (String name : claims(type).keySet()) {
				String json = "{\"sub\":\"" + USER_ID + "\",\"jti\":\"" + TOKEN_ID
						+ "\",\"iat\":" + Instant.now().getEpochSecond() + ",\"exp\":"
						+ Instant.now().plusSeconds(60).getEpochSecond() + ",\"token_use\":\""
						+ type + "\",\"family_id\":\"" + FAMILY_ID + "\"}";
				json = json.replaceAll("\"" + name + "\":(\"[^\"]*\"|[0-9]+)", "\"" + name + "\":{}");
				String token = Jwts.builder().content(json).signWith(
						key(type.equals("access") ? accessSecret : refreshSecret), Jwts.SIG.HS256).compact();
				assertThrows(JwtException.class, () -> parse(token, type));
			}
			Map<String, Object> invalid = claims(type);
			invalid.put("iat", invalid.get("exp"));
			assertThrows(JwtException.class, () -> parse(sign(invalid, type), type));
		}
	}

	@Test
	void rejectsUnsafeKeysWithoutIncludingSecretsInErrors() {
		for (String invalid : new String[] { null, "", " ", "not-base64!", "c2hvcnQ=" }) {
			var accessError = assertThrows(IllegalArgumentException.class, () ->
					new JwtService(invalid, refreshSecret, Duration.ofMinutes(15), Duration.ofDays(7)));
			assertThrows(IllegalArgumentException.class, () ->
					new JwtService(accessSecret, invalid, Duration.ofMinutes(15), Duration.ofDays(7)));
			if (invalid != null && !invalid.isBlank()) assertFalse(accessError.getMessage().contains(invalid));
		}
		assertThrows(IllegalArgumentException.class, () ->
				new JwtService(accessSecret, accessSecret, Duration.ofMinutes(15), Duration.ofDays(7)));
	}

	@Test
	void rejectsInvalidTtlsAndMissingGenerationArguments() {
		for (Duration invalid : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1),
				Duration.ofMillis(500), Duration.ofSeconds(Long.MAX_VALUE) }) {
			assertThrows(IllegalArgumentException.class, () ->
					new JwtService(accessSecret, refreshSecret, invalid, Duration.ofDays(7)));
			assertThrows(IllegalArgumentException.class, () ->
					new JwtService(accessSecret, refreshSecret, Duration.ofMinutes(15), invalid));
		}
		assertThrows(NullPointerException.class, () -> service.generateAccessToken(null));
		assertThrows(NullPointerException.class, () -> service.generateRefreshToken(null, TOKEN_ID, FAMILY_ID));
		assertThrows(NullPointerException.class, () -> service.generateRefreshToken(USER_ID, null, FAMILY_ID));
		assertThrows(NullPointerException.class, () -> service.generateRefreshToken(USER_ID, TOKEN_ID, null));
	}

	private Map<String, Object> claims(String type) {
		Map<String, Object> claims = new HashMap<>();
		claims.put("sub", USER_ID.toString());
		claims.put("jti", TOKEN_ID.toString());
		claims.put("iat", Date.from(Instant.now().minusSeconds(10)));
		claims.put("exp", Date.from(Instant.now().plusSeconds(60)));
		claims.put("token_use", type);
		if (type.equals("refresh")) claims.put("family_id", FAMILY_ID.toString());
		return claims;
	}

	private String sign(Map<String, Object> claims, String type) {
		return Jwts.builder().claims(claims)
				.signWith(key(type.equals("access") ? accessSecret : refreshSecret), Jwts.SIG.HS256).compact();
	}

	private Claims parse(String token, String type) {
		return type.equals("access") ? service.parseAccessToken(token) : service.parseRefreshToken(token);
	}

	private SecretKey key(String secret) {
		return Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
	}

	private Duration lifetime(Claims claims) {
		return Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
	}
}
