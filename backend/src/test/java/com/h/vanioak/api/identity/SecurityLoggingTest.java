package com.h.vanioak.api.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggerGroups;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.exception.GlobalExceptionHandler;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.UserFacade;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

class SecurityLoggingTest {

	private static final String PASSWORD = "pwd_SENTINEL_42";
	private static final String API_KEY = "api_SENTINEL_42";
	private static final String ACCESS = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJsb2ctdGVzdCJ9.signature";
	private static final String REFRESH = "eyJ0eXAiOiJyZWZyZXNoIn0.eyJqdGkiOiJ0ZXN0In0.signature";
	private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private final LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
	private final Logger root = logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
	private final Map<String, Level> originalLevels = new HashMap<>();
	private final List<Appender<ILoggingEvent>> originalAppenders = new ArrayList<>();
	private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
	private final AuthFacade auth = mock(AuthFacade.class);
	private final UserFacade users = mock(UserFacade.class);
	private final ApiKeyFacade keys = mock(ApiKeyFacade.class);
	private MockMvc mvc;

	@BeforeEach
	void captureInMemoryOnly() {
		logging.getLoggerList().forEach(logger -> originalLevels.put(logger.getName(), logger.getLevel()));
		root.iteratorForAppenders().forEachRemaining(originalAppenders::add);
		originalAppenders.forEach(root::detachAppender);
		captured.start(); root.addAppender(captured);
		var result = new AuthFacade.LoginResult(ACCESS, REFRESH, "Bearer", 900,
				new AuthFacade.UserSummary(ID, "admin", "ADMIN"));
		when(auth.login(anyString(), anyString())).thenReturn(result);
		when(auth.refresh(anyString())).thenReturn(result);
		var user = new UserFacade.UserView(ID, "engineer", "ENGINEER", UserFacade.Status.ACTIVE);
		when(users.create(any())).thenReturn(user);
		when(users.update(eq(ID), any())).thenReturn(user);
		var credential = new ApiKeyFacade.CredentialView(ID, ID, "prefix", ApiKeyFacade.Status.ACTIVE,
				null, Instant.parse("2026-10-28T00:00:00Z"), null);
		when(keys.create(eq(ID), eq(ID), eq(ID), isNull())).thenReturn(new ApiKeyFacade.IssuedKey(credential, API_KEY));
		when(keys.rotate(ID, ID)).thenReturn(new ApiKeyFacade.IssuedKey(credential, API_KEY));
		mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new UserController(users), new ApiKeyController(keys))
				.setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler()).build();
	}

	@AfterEach
	void restoreLogging() {
		root.detachAppender(captured); captured.stop();
		logging.getLoggerList().forEach(logger -> logger.setLevel(originalLevels.get(logger.getName())));
		originalAppenders.forEach(root::addAppender);
	}

	@SuppressWarnings("unchecked")
	private void configure(boolean usePolicy, boolean trace) {
		var environment = new MockEnvironment().withProperty("debug", "release")
				.withProperty("trace", Boolean.toString(trace)).withProperty("logging.level.root", "INFO");
		if (usePolicy) {
			var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
			var properties = yaml.getObject(); assertNotNull(properties);
			var policy = new Properties();
			properties.forEach((key, value) -> { if (key.toString().startsWith("logging.")) policy.put(key, value); });
			environment.getPropertySources().addFirst(new PropertiesPropertySource("installed-logging-policy", policy));
		}
		var listener = new LoggingApplicationListener();
		var groups = (Map<String, List<String>>) ReflectionTestUtils.getField(LoggingApplicationListener.class, "DEFAULT_GROUP_LOGGERS");
		assertNotNull(groups);
		ReflectionTestUtils.setField(listener, "loggerGroups", new LoggerGroups(groups));
		ReflectionTestUtils.invokeMethod(listener, "initializeEarlyLoggingLevel", environment);
		ReflectionTestUtils.invokeMethod(listener, "initializeFinalLoggingLevels", environment, LoggingSystem.get(getClass().getClassLoader()));
		captured.list.clear();
	}

	private void login() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.access_token").value(ACCESS))
				.andExpect(jsonPath("$.data.refresh_token").value(REFRESH));
	}

	private String messages() {
		return captured.list.stream().map(event -> event.getFormattedMessage()
				+ (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
				.collect(java.util.stream.Collectors.joining("\n"));
	}

	private void assertNoCredentials() {
		String messages = messages();
		for (String secret : List.of(PASSWORD, API_KEY, ACCESS, REFRESH)) {
			assertFalse(messages.contains(secret), "Credential appeared in logging");
			assertFalse(messages.contains(secret.substring(0, 8)), "Truncated credential appeared in logging");
		}
	}

	@Test
	void reproducesAmbientDebugReleaseAndPreventsDtoLoggingWithInstalledPolicy() throws Exception {
		configure(false, false);
		assertTrue(logging.getLogger("org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor").isDebugEnabled());
		login(); assertTrue(messages().contains(PASSWORD), "Unsafe baseline must reproduce actual DTO logging");
		configure(true, false);
		assertEquals(Level.INFO, logging.getLogger("org.springframework.web").getEffectiveLevel());
		login(); assertNoCredentials();
		verify(auth, times(2)).login("admin", PASSWORD);
	}

	@Test
	void traceModeKeepsPasswordTokenAndApiKeyHttpContractsWithoutLoggingCredentials() throws Exception {
		configure(true, true);
		login();
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refresh_token\":\"" + REFRESH + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.access_token").value(ACCESS))
				.andExpect(jsonPath("$.data.refresh_token").value(REFRESH));
		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + ACCESS)
				.header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
				.content("{\"refresh_token\":\"" + REFRESH + "\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"engineer\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated());
		mvc.perform(patch("/api/v1/users/" + ID).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/applications/" + ID + "/environments/" + ID + "/api-keys").principal(ID::toString))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.api_key").value(API_KEY));
		mvc.perform(post("/api/v1/api-keys/" + ID + "/rotate").principal(ID::toString))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.api_key").value(API_KEY));
		assertNoCredentials();
		verify(auth).logout(REFRESH, ACCESS);
		assertEquals(Level.INFO, logging.getLogger("org.springframework.http").getEffectiveLevel());
	}

	@Test
	void malformedValidationAndUnexpectedFailuresKeepOnlySafeDiagnostics() throws Exception {
		configure(true, true);
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":{\"secret\":\"" + PASSWORD + "\"}}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + "a".repeat(101) + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest());
		when(auth.login(anyString(), anyString())).thenThrow(new IllegalStateException(PASSWORD + API_KEY + ACCESS + REFRESH));
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("An unexpected error occurred"));
		var operational = LoggerFactory.getLogger("com.h.vanioak.security.logging-check");
		operational.info("Operational checkpoint route=auth"); operational.warn("Dependency unavailable type=TimeoutException");
		assertTrue(messages().contains("Unexpected request failure, type: java.lang.IllegalStateException, stack trace:"));
		assertTrue(messages().contains("Operational checkpoint route=auth"));
		assertTrue(messages().contains("Dependency unavailable type=TimeoutException"));
		assertNoCredentials();
	}

	@Test
	void fallbackResolverCannotWarnWithRejectedPasswordValue() throws Exception {
		configure(true, false);
		var fallback = MockMvcBuilders.standaloneSetup(new ValidationController()).build();
		fallback.perform(post("/logging-validation").contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest());
		assertEquals(Level.OFF, logging.getLogger("org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver").getEffectiveLevel());
		assertNoCredentials();
	}

	@RestController
	static class ValidationController {
		@PostMapping("/logging-validation")
		void validate(@Valid @RequestBody LimitedPassword request) { }
	}
	private record LimitedPassword(@Size(max = 1) String password) { }
}
