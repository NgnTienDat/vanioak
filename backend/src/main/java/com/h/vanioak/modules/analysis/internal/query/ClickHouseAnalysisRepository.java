package com.h.vanioak.modules.analysis.internal.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.LogView;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.HealthMetric;
import com.h.vanioak.modules.analysis.internal.search.LogCursorCodec.Key;

public class ClickHouseAnalysisRepository {
	public static final String SETTINGS = " SETTINGS max_threads=1,max_execution_time=4,max_memory_usage=67108864,max_result_bytes=16777216,max_result_rows=100000,result_overflow_mode='throw' FORMAT JSONEachRow";
	private final ClickHouseReadClient client;
	private final ObjectMapper mapper;

	public ClickHouseAnalysisRepository(ClickHouseReadClient client, ObjectMapper mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	public List<LogView> search(AuthorizedScope scope, SearchFilters f, TimeRange range, Key key) {
		if (scope.applicationIds().isEmpty())
			return List.of();
		var p = parameters(scope, range);
		var sql = new StringBuilder(
				"SELECT event_id,application_id,environment_id,host_ip,level,message,timestamp,trace_id,metadata_json,received_at,processed_at FROM logs FINAL WHERE ")
				.append(predicate(scope));
		if (f.level() != null) {
			sql.append(" AND level={level:String}");
			p.put("level", f.level());
		}
		if (f.traceId() != null) {
			sql.append(" AND trace_id={trace:String}");
			p.put("trace", f.traceId());
		}
		if (f.message() != null) {
			sql.append(" AND position(message,{message:String})>0");
			p.put("message", f.message());
		}
		if (key != null) {
			sql.append(
					" AND (timestamp,event_id,application_id,environment_id,level)<(fromUnixTimestamp64Milli({last_ts:Int64}),{last_event:UUID},{last_app:UUID},{last_env:UUID},{last_level:String})");
			p.put("last_ts", Long.toString(key.timestamp()));
			p.put("last_event", key.eventId().toString());
			p.put("last_app", key.applicationId().toString());
			p.put("last_env", key.environmentId().toString());
			p.put("last_level", key.level());
		}
		sql.append(
				" ORDER BY timestamp DESC,event_id DESC,application_id DESC,environment_id DESC,level DESC LIMIT {fetch:UInt32}");
		p.put("fetch", Integer.toString(f.limit() + 1));
		var result = new ArrayList<LogView>();
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		try {
			for (String line : lines(client.query(sql + SETTINGS, p))) {
				if (System.nanoTime() > deadline || result.size() >= f.limit() + 1)
					throw new IllegalStateException();
				var n = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
				var metadata = n.get("metadata_json");
				var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
						.readTree(metadata.asString());
				if (!node.isObject())
					throw new IllegalStateException();
				@SuppressWarnings("unchecked")
				Map<String, Object> values = mapper.convertValue(node, Map.class);
				var row = new LogView(UUID.fromString(n.get("event_id").asString()),
						UUID.fromString(n.get("application_id").asString()),
						UUID.fromString(n.get("environment_id").asString()), n.get("host_ip").asString(),
						n.get("level").asString(), n.get("message").asString(), time(n.get("timestamp").asString()),
						n.get("trace_id").asString(),
						java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values)),
						time(n.get("received_at").asString()), time(n.get("processed_at").asString()));
				if (!List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(row.level())
						|| row.timestamp().getNano() % 1000000 != 0
						|| !scope.permits(row.applicationId(), row.environmentId()))
					throw new IllegalStateException();
				result.add(row);
			}
			if (System.nanoTime() > deadline)
				throw new IllegalStateException();
			return List.copyOf(result);
		} catch (Exception ex) {
			throw new AnalysisException(ErrorCode.UNAVAILABLE);
		}
	}

	public List<HealthMetric> health(AuthorizedScope scope, TimeRange range) {
		if (scope.applicationIds().isEmpty())
			return List.of();
		var sql = "SELECT application_id,environment_id,toStartOfHour(timestamp,'UTC') AS hour_start,count() AS total_logs,countIf(level='ERROR') AS error_logs,countIf(level='CRITICAL') AS critical_logs FROM logs FINAL WHERE "
				+ predicate(scope)
				+ " GROUP BY application_id,environment_id,hour_start ORDER BY application_id,environment_id,hour_start"
				+ SETTINGS;
		var result = new ArrayList<HealthMetric>();
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		try {
			for (String line : lines(client.query(sql, parameters(scope, range)))) {
				if (System.nanoTime() > deadline || result.size() >= 100000)
					throw new IllegalStateException();
				var n = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
				var app = UUID.fromString(n.get("application_id").asString());
				var env = UUID.fromString(n.get("environment_id").asString());
				var hour = time(n.get("hour_start").asString());
				long total = count(n.get("total_logs")), errors = count(n.get("error_logs")),
						critical = count(n.get("critical_logs"));
				long failed = Math.addExact(errors, critical);
				if (!scope.permits(app, env) || total <= 0 || failed > total
						|| !hour.equals(hour.truncatedTo(java.time.temporal.ChronoUnit.HOURS)))
					throw new IllegalStateException();
				result.add(new HealthMetric(app, env, hour, total, errors, critical, failed / (double) total));
			}
			if (System.nanoTime() > deadline)
				throw new IllegalStateException();
			return List.copyOf(result);
		} catch (Exception ex) {
			throw new AnalysisException(ErrorCode.UNAVAILABLE);
		}
	}

	private static long count(tools.jackson.databind.JsonNode value) {
		if (value == null || !(value.isIntegralNumber() || value.isString()) || !value.asString().matches("[0-9]+"))
			throw new IllegalStateException();
		return Long.parseLong(value.asString());
	}

	static String predicate(AuthorizedScope s) {
		return "application_id IN {apps:Array(UUID)} AND timestamp>=fromUnixTimestamp64Milli({from_ms:Int64}) AND timestamp<fromUnixTimestamp64Milli({to_ms:Int64})"
				+ (s.environmentId() == null ? "" : " AND environment_id={env:UUID}");
	}

	static Map<String, String> parameters(AuthorizedScope s, TimeRange r) {
		var p = new LinkedHashMap<String, String>();
		p.put("apps",
				"[" + String.join(",",
						s.applicationIds().stream().map(UUID::toString).sorted().map(v -> "'" + v + "'").toList())
						+ "]");
		p.put("from_ms", Long.toString(r.fromMillis()));
		p.put("to_ms", Long.toString(r.toMillis()));
		if (s.environmentId() != null)
			p.put("env", s.environmentId().toString());
		return p;
	}

	static Iterable<String> lines(byte[] bytes) {
		if (bytes.length > 0 && bytes[bytes.length - 1] != 10)
			throw new AnalysisException(ErrorCode.UNAVAILABLE);
		var reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes),
				StandardCharsets.UTF_8.newDecoder()));
		return reader.lines()::iterator;
	}

	static Instant time(String value) {
		return Instant.parse(value.replace(' ', 'T') + (value.endsWith("Z") ? "" : "Z"));
	}
}
