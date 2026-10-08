package com.h.vanioak.modules.identity.internal.user;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.h.vanioak.BackendApplication;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.internal.auth.JwtService;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenEntity;
import com.h.vanioak.modules.identity.internal.refreshtoken.RefreshTokenRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application.properties")
class IdentityAcceptanceIT {

	private static final String ADMIN = "identity-e2e-admin-" + UUID.randomUUID();
	private static final String ENGINEER = "identity-e2e-engineer-" + UUID.randomUUID();
	private static final String ADMIN_PASSWORD = "test-only-" + UUID.randomUUID();
	private static final String ENGINEER_PASSWORD = "test-only-" + UUID.randomUUID();
	private static final Set<String> TOKEN_FIELDS = Set.of("access_token", "refresh_token", "token_type", "expires_in");

	@DynamicPropertySource
	static void dedicatedInfrastructure(DynamicPropertyRegistry registry) {
		if (!"true".equalsIgnoreCase(System.getenv("IDENTITY_E2E_ISOLATION_CONFIRMED"))) {
			throw new IllegalStateException("Dedicated E2E PostgreSQL and Redis isolation must be confirmed before startup");
		}
		// Read every required setting before registering any startup configuration. No deployment fallback.
		String url = required("IDENTITY_E2E_POSTGRES_URL");
		String username = required("IDENTITY_E2E_POSTGRES_USERNAME");
		String password = required("IDENTITY_E2E_POSTGRES_PASSWORD");
		String host = required("IDENTITY_E2E_REDIS_HOST");
		String port = required("IDENTITY_E2E_REDIS_PORT");
		String database = required("IDENTITY_E2E_REDIS_DATABASE");
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> username);
		registry.add("spring.datasource.password", () -> password);
		registry.add("spring.data.redis.host", () -> host);
		registry.add("spring.data.redis.port", () -> port);
		registry.add("spring.data.redis.database", () -> database);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("vanioak.identity.bootstrap-admin.enabled", () -> "true");
		registry.add("vanioak.identity.bootstrap-admin.username", () -> ADMIN);
		registry.add("vanioak.identity.bootstrap-admin.password", () -> ADMIN_PASSWORD);
		// Unused infrastructure needs configuration, but this Identity flow makes no calls to it.
		registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
		registry.add("spring.rabbitmq.username", () -> "test-only");
		registry.add("spring.rabbitmq.password", () -> "test-only");
		registry.add("vanioak.clickhouse.url", () -> "http://127.0.0.1:8123");
		registry.add("vanioak.clickhouse.username", () -> "test-only");
		registry.add("vanioak.clickhouse.password", () -> "test-only");
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalStateException("Required E2E setting is missing: " + name);
		return value;
	}

	@LocalServerPort
	private int port;
	@Autowired
	private ObjectMapper json;
	@Autowired
	private JwtService jwt;
	@Autowired
	private UserRepository users;
	@Autowired
	private RefreshTokenRepository tokens;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private StringRedisTemplate redis;
	private final List<String> ownedKeys = new ArrayList<>();

	@Test
	void identityLifecycleThroughRealHttpAndCommittedStorage() throws Exception {
		var admin = transaction().execute(status -> users.findByUsername(ADMIN).orElseThrow());
		assertEquals("ADMIN", admin.getRole());
		assertEquals(Status.ACTIVE, admin.getStatus());
		assertTrue(admin.getPasswordHash().startsWith("$2"));
		assertTrue(encoder.matches(ADMIN_PASSWORD, admin.getPasswordHash()));

		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			var login = request(client, "POST", "/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD), null, 200);
			assertEquals(Set.of("access_token", "refresh_token", "token_type", "expires_in", "user"), fields(login));
			assertEquals(Set.of("id", "username", "role"), fields(login.path("user")));
			assertEquals(admin.getId().toString(), login.path("user").path("id").asString());
			assertEquals("ADMIN", login.path("user").path("role").asString());
			String access = login.path("access_token").asString();
			String refresh = login.path("refresh_token").asString();
			request(client, "GET", "/users", null, access, 200);

			var engineer = request(client, "POST", "/users", Map.of("username", ENGINEER, "password", ENGINEER_PASSWORD), access, 201);
			assertEquals("ENGINEER", engineer.path("role").asString());
			var engineerLogin = request(client, "POST", "/auth/login", Map.of("username", ENGINEER, "password", ENGINEER_PASSWORD), null, 200);
			request(client, "GET", "/users", null, engineerLogin.path("access_token").asString(), 403);

			// Same JWT; only the committed current database role/status changes between requests.
			currentAdmin(admin.getId(), "ENGINEER", Status.ACTIVE);
			request(client, "GET", "/users", null, access, 403);
			currentAdmin(admin.getId(), "ADMIN", Status.DISABLED);
			request(client, "GET", "/users", null, access, 403);
			currentAdmin(admin.getId(), "ADMIN", Status.ACTIVE);
			request(client, "GET", "/users", null, access, 200);

			var original = jwt.parseRefreshToken(refresh);
			var rotated = request(client, "POST", "/auth/refresh", Map.of("refresh_token", refresh), null, 200);
			assertEquals(TOKEN_FIELDS, fields(rotated));
			String childRefresh = rotated.path("refresh_token").asString();
			var childClaims = jwt.parseRefreshToken(childRefresh);
			UUID originalId = UUID.fromString(original.getId());
			UUID childId = UUID.fromString(childClaims.getId());
			UUID family = UUID.fromString(original.get("family_id", String.class));
			assertNotEquals(originalId, childId);
			assertNotEquals(jwt.parseAccessToken(access).getId(), jwt.parseAccessToken(rotated.path("access_token").asString()).getId());
			var oldRow = token(originalId);
			var child = token(childId);
			assertTrue(oldRow.isUsed());
			assertFalse(oldRow.isRevoked());
			assertEquals(originalId, child.getParentTokenId());
			assertEquals(family, child.getFamilyId());
			assertEquals(admin.getId(), child.getUserId());
			assertEquals(childClaims.getExpiration().toInstant(), child.getExpiresAt());
			assertFalse(child.isUsed());
			assertFalse(child.isRevoked());

			request(client, "POST", "/auth/refresh", Map.of("refresh_token", refresh), null, 401);
			assertTrue(token(originalId).isRevoked());
			assertTrue(token(childId).isRevoked());
			request(client, "POST", "/auth/refresh", Map.of("refresh_token", childRefresh), null, 401);

			var fresh = request(client, "POST", "/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD), null, 200);
			String logoutAccess = fresh.path("access_token").asString();
			String logoutRefresh = fresh.path("refresh_token").asString();
			var logoutClaims = jwt.parseRefreshToken(logoutRefresh);
			assertNotEquals(family.toString(), logoutClaims.get("family_id", String.class));
			assertTrue(request(client, "POST", "/auth/logout", Map.of("refresh_token", logoutRefresh), logoutAccess, 200).isNull());
			var logoutRow = token(UUID.fromString(logoutClaims.getId()));
			assertTrue(logoutRow.isRevoked());
			assertFalse(logoutRow.isUsed());
			var accessClaims = jwt.parseAccessToken(logoutAccess);
			String key = "vanioak:identity:access-blacklist:" + accessClaims.getId();
			assertEquals("1", redis.opsForValue().get(key));
			Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
			assertNotNull(ttl);
			assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(15).toMillis());
			assertEquals(Long.valueOf(accessClaims.getExpiration().toInstant().toEpochMilli()),
					redis.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key)));
			request(client, "GET", "/users", null, logoutAccess, 401);
			request(client, "POST", "/auth/refresh", Map.of("refresh_token", logoutRefresh), null, 401);
		}
	}

	private JsonNode request(HttpClient client, String method, String path, Map<String, String> body,
			String access, int expectedStatus) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
				.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
		if (access != null) builder.header("Authorization", "Bearer " + access);
		var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
		var envelope = json.readTree(response.body());
		var data = envelope.path("data");
		if (data.has("access_token")) {
			ownedKeys.add("vanioak:identity:access-blacklist:" + jwt.parseAccessToken(data.path("access_token").asString()).getId());
			assertEquals("Bearer", data.path("token_type").asString());
			assertEquals(900, data.path("expires_in").asLong());
		}
		assertEquals(expectedStatus, response.statusCode(), "HTTP " + method + " " + path);
		assertEquals(Set.of("success", "message", "data"), fields(envelope));
		assertEquals(expectedStatus < 400, envelope.path("success").asBoolean());
		assertEquals(expectedStatus == 401 ? "Invalid credentials" : expectedStatus == 403 ? "Access denied" : "Success",
				envelope.path("message").asString());
		if (expectedStatus >= 400) assertTrue(data.isNull());
		assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
		assertFalse(response.body().contains(ADMIN_PASSWORD));
		assertFalse(response.body().contains(ENGINEER_PASSWORD));
		assertFalse(response.body().contains("password_hash"));
		return data;
	}

	private Set<?> fields(JsonNode node) {
		return json.convertValue(node, Map.class).keySet();
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(transactionManager);
	}

	private RefreshTokenEntity token(UUID id) {
		return transaction().execute(status -> tokens.findById(id).orElseThrow());
	}

	private void currentAdmin(UUID id, String role, Status userStatus) {
		transaction().executeWithoutResult(status -> {
			assertEquals(1, entityManager.createQuery("update UserEntity u set u.role = :role, u.status = :status where u.id = :id")
					.setParameter("role", role).setParameter("status", userStatus).setParameter("id", id).executeUpdate());
			entityManager.flush();
			entityManager.clear();
		});
	}

	@AfterEach
	void cleanupOwnedFixtures() {
		// This cannot run if context startup fails after bootstrap; that case needs dedicated-environment cleanup.
		assertAll(() -> {
			if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
		}, () -> {
			transaction().executeWithoutResult(status -> {
				var ids = entityManager.createQuery("select u.id from UserEntity u where u.username in :names", UUID.class)
						.setParameter("names", List.of(ADMIN, ENGINEER)).getResultList();
				if (!ids.isEmpty()) {
					entityManager.createQuery("delete from RefreshTokenEntity t where t.userId in :ids")
							.setParameter("ids", ids).executeUpdate();
					entityManager.createQuery("delete from UserEntity u where u.id in :ids")
							.setParameter("ids", ids).executeUpdate();
				}
			});
		});
	}
}
