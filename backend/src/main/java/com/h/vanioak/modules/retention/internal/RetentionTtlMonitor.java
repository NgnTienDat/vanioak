package com.h.vanioak.modules.retention.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public class RetentionTtlMonitor {
	private static final Logger log = LoggerFactory.getLogger(RetentionTtlMonitor.class);
	private static final String QUERY = "SELECT create_table_query FROM system.tables "
			+ "WHERE database = currentDatabase() AND name = 'logs' LIMIT 1 "
			+ "SETTINGS max_threads=1,max_execution_time=4,max_result_rows=1,"
			+ "max_result_bytes=8192,result_overflow_mode='throw' FORMAT JSONEachRow";
	private static final Pattern EXPECTED = Pattern.compile(
			"(?i)^`?timestamp`?\\s*\\+\\s*(?:INTERVAL\\s+7\\s+DAY|toIntervalDay\\s*\\(\\s*7\\s*\\))\\s*(?:DELETE)?$");
	private static final Pattern CLEARLY_DIFFERENT = Pattern.compile(
			"(?i)^`?[a-z_][a-z_0-9]*`?\\s*\\+\\s*(?:INTERVAL\\s+[0-9]+\\s+DAY|toIntervalDay\\s*\\(\\s*[0-9]+\\s*\\))\\s*(?:DELETE)?$");

	enum Status { OK, MISMATCH, UNKNOWN }
	record Result(Status status, String reason, Instant checkedAt) { }

	private final ClickHouseConnection clickHouse;
	private final ObjectMapper mapper;
	private volatile Result latest = new Result(Status.UNKNOWN, "NOT_CHECKED", Instant.EPOCH);

	RetentionTtlMonitor(ClickHouseConnection clickHouse, ObjectMapper mapper) {
		this.clickHouse = clickHouse;
		this.mapper = mapper;
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	void scheduled() {
		Result result = probe();
		if (result.status() == Status.OK)
			log.info("Retention TTL status={} reason={} checked_at={}", result.status(), result.reason(), result.checkedAt());
		else
			log.warn("Retention TTL status={} reason={} checked_at={}", result.status(), result.reason(), result.checkedAt());
	}

	synchronized Result probe() {
		Status status;
		String reason;
		try {
			String response = clickHouse.queryReadOnly(QUERY);
			if (response.isBlank()) {
				status = Status.UNKNOWN;
				reason = "NO_METADATA";
			} else {
				try {
					var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
							.readTree(response);
					if (node == null || !node.isObject() || node.size() != 1 || !node.path("create_table_query").isString()) {
						status = Status.UNKNOWN;
						reason = "INVALID_METADATA";
					} else {
						String ddl = node.path("create_table_query").asString();
						status = classify(ddl);
						reason = status == Status.OK ? "POLICY_MATCH" : status == Status.MISMATCH ? "POLICY_MISMATCH" : "UNRECOGNIZED_DDL";
					}
				} catch (JacksonException ex) {
					status = Status.UNKNOWN;
					reason = "INVALID_METADATA";
				}
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			status = Status.UNKNOWN;
			reason = "QUERY_INTERRUPTED";
		} catch (IOException | RuntimeException ex) {
			status = Status.UNKNOWN;
			reason = "QUERY_UNAVAILABLE";
		}
		Result result = new Result(status, reason, Instant.now());
		latest = result;
		return result;
	}

	Result latest() { return latest; }

	static Status classify(String ddl) {
		if (ddl == null || ddl.isBlank() || ddl.length() > 8192 || !ddl.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE ")
				|| ddl.contains("--") || ddl.contains("/*"))
			return Status.UNKNOWN;
		List<Integer> ttl = new ArrayList<>();
		int columnsTtl = 0;
		int settings = ddl.length();
		int depth = 0;
		char quote = 0;
		boolean columns = false;
		boolean engine = false;
		for (int i = 0; i < ddl.length();) {
			char c = ddl.charAt(i);
			if (quote != 0) {
				if (c == '\\' && i + 1 < ddl.length()) { i += 2; continue; }
				if (c == quote) {
					if (i + 1 < ddl.length() && ddl.charAt(i + 1) == quote) { i += 2; continue; }
					quote = 0;
				}
				i++;
				continue;
			}
			if (c == '\'' || c == '"' || c == '`') { quote = c; i++; continue; }
			if (c == '(') { if (depth == 0 && !engine) columns = true; depth++; i++; continue; }
			if (c == ')') { if (--depth < 0) return Status.UNKNOWN; i++; continue; }
			if (!Character.isLetter(c) && c != '_') { i++; continue; }
			int start = i++;
			while (i < ddl.length() && (Character.isLetterOrDigit(ddl.charAt(i)) || ddl.charAt(i) == '_')) i++;
			String word = ddl.substring(start, i);
			if (word.equalsIgnoreCase("ENGINE") && depth == 0) engine = true;
			if (word.equalsIgnoreCase("TTL")) {
				if (depth == 0) ttl.add(i);
				else columnsTtl++;
			} else if (word.equalsIgnoreCase("SETTINGS") && depth == 0 && settings == ddl.length()) settings = start;
		}
		if (quote != 0 || depth != 0 || !columns || !engine) return Status.UNKNOWN;
		if (columnsTtl > 0 || ttl.size() > 1) return Status.MISMATCH;
		if (ttl.isEmpty()) return Status.MISMATCH;
		if (ttl.getFirst() >= settings) return Status.UNKNOWN;
		String rule = ddl.substring(ttl.getFirst(), settings).trim();
		if (rule.endsWith(";")) rule = rule.substring(0, rule.length() - 1).trim();
		if (EXPECTED.matcher(rule).matches()) return Status.OK;
		if (CLEARLY_DIFFERENT.matcher(rule).matches() || rule.contains(",")
				|| Pattern.compile("(?i)\\b(WHERE|GROUP|MOVE|RECOMPRESS)\\b").matcher(rule).find())
			return Status.MISMATCH;
		return Status.UNKNOWN;
	}
}
