package com.h.vanioak;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BackendApplicationTests {

	@Test
	void migrationsMatchAuthoritativeContract() throws Exception {
		Path backend = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
		String contract = Files.readString(backend.resolve("../docs/contracts/postgres-schema.sql"));
		String migration = Files.readString(backend.resolve(
				"src/main/resources/db/migration/V1__initial_postgresql_schema.sql"));
		String refreshMigration = Files.readString(backend.resolve(
				"src/main/resources/db/migration/V2__refresh_tokens.sql"));
		String combinedSchema = migration.replace("\r\n", "\n").replace("CREATE TABLE applications",
				refreshMigration.replace("\r\n", "\n").stripTrailing() + "\n\nCREATE TABLE applications");
		assertEquals(contract.replace("\r\n", "\n"), combinedSchema,
				"Versioned migrations must preserve the authoritative PostgreSQL contract");
	}

}
