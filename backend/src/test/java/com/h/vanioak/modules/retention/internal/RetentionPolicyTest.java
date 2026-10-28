package com.h.vanioak.modules.retention.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RetentionPolicyTest {
	@Test
	void defaultsToSevenDays() {
		try (var context = context(null)) {
			assertEquals(7, context.getBean(RetentionPolicy.class).days());
		}
	}

	@Test
	void acceptsExplicitSevenDays() {
		try (var context = context("7")) {
			assertEquals(7, context.getBean(RetentionPolicy.class).days());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "8", "7.5", "seven", "2147483648", "", " 7"})
	void rejectsUnsupportedConfiguration(String value) {
		var error = assertThrows(RuntimeException.class, () -> {
			try (var ignored = context(value)) {
				fail("Invalid retention configuration was accepted");
			}
		});
		while (error.getCause() != null)
			error = (RuntimeException) error.getCause();
		assertTrue(error.getMessage().contains("LOG_RETENTION_DAYS must be 7"));
	}

	@Test
	void logsTableHasUnconditionalSevenDayTimestampTtl() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isRegularFile(root.resolve("docs/contracts/clickhouse-schema.sql")))
			root = root.getParent();
		assertNotNull(root, "Cannot locate checked-in ClickHouse schema");
		String ddl = Files.readString(root.resolve("docs/contracts/clickhouse-schema.sql"));
		String sql = ddl.replaceAll("(?m)^--[^\\r\\n]*", "");
		var table = Pattern.compile("(?is)\\bCREATE\\s+TABLE\\s+logs\\b(.*?)\\bSETTINGS\\b").matcher(sql);
		assertTrue(table.find(), "logs table contract missing");
		String definition = table.group(1);
		assertEquals(1, Pattern.compile("(?im)^\\s*TTL\\b").matcher(definition).results().count());
		assertEquals(1, Pattern.compile("(?im)^\\s*TTL\\s+timestamp\\s*\\+\\s*INTERVAL\\s+7\\s+DAY\\s+DELETE\\s*$")
				.matcher(definition).results().count());
		assertFalse(Pattern.compile("(?i)\\bWHERE\\b").matcher(definition).find());
	}

	private static AnnotationConfigApplicationContext context(String days) {
		var context = new AnnotationConfigApplicationContext();
		var environment = new MockEnvironment();
		if (days != null)
			environment.setProperty("LOG_RETENTION_DAYS", days);
		context.setEnvironment(environment);
		context.register(RetentionPolicy.class);
		context.refresh();
		return context;
	}
}
