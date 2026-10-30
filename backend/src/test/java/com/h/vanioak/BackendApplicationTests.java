package com.h.vanioak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		String auditMigration = Files.readString(backend.resolve(
				"src/main/resources/db/migration/V3__incident_resolution_audit.sql")).replace("\r\n", "\n");
		assertTrue(auditMigration.startsWith("ALTER TABLE incidents\n"
				+ "    ADD COLUMN resolution_source VARCHAR(20),\n"
				+ "    ADD COLUMN resolved_by_user_id UUID REFERENCES users(id),\n    ADD CONSTRAINT "));
		String auditConstraint = auditMigration.substring(auditMigration.indexOf("ADD CONSTRAINT ")
				+ "ADD CONSTRAINT ".length(), auditMigration.indexOf(';'));
		String auditIndex = auditMigration.substring(auditMigration.indexOf("CREATE INDEX")).stripTrailing();
		combinedSchema = combinedSchema.replace("    resolved_at         TIMESTAMPTZ,\n",
				"    resolved_at         TIMESTAMPTZ,\n"
				+ "    resolution_source   VARCHAR(20),\n"
				+ "    resolved_by_user_id  UUID REFERENCES users(id),\n")
				.replace("        CHECK (error_count >= 0)\n);",
						"        CHECK (error_count >= 0),\n    CONSTRAINT " + auditConstraint + "\n);")
				.replace("-- Alert accounting receipts only:", auditIndex + "\n\n-- Alert accounting receipts only:");
		assertEquals(contract.replace("\r\n", "\n"), combinedSchema,
				"Versioned migrations must preserve the authoritative PostgreSQL contract");
	}

}
