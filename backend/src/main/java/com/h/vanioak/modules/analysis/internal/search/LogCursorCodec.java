package com.h.vanioak.modules.analysis.internal.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;

public class LogCursorCodec {
	private final ObjectMapper mapper;

	public LogCursorCodec(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public record Key(long timestamp, UUID eventId, UUID applicationId, UUID environmentId, String level) {
	}

	public record Cursor(Key key, TimeRange range, String context) {
	}

	public String fingerprint(AuthorizedScope scope, SearchFilters f, TimeRange range) {
		try {
			var values = new LinkedHashMap<String, Object>();
			values.put("actor", scope.actorId());
			values.put("apps", scope.applicationIds().stream().map(UUID::toString).sorted().toList());
			values.put("app", scope.requestedApplicationId());
			values.put("env", scope.environmentId());
			values.put("level", f.level());
			values.put("trace", f.traceId());
			values.put("message", f.message());
			values.put("from", f.from() == null ? null : f.from().toString());
			values.put("to", f.to() == null ? null : f.to().toString());
			values.put("window_from", range.from().toString());
			values.put("window_to", range.to().toString());
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(values)));
		} catch (Exception ex) {
			throw new AnalysisException(ErrorCode.INVALID_CURSOR);
		}
	}

	public String encode(Key key, TimeRange range, String context) {
		var values = new LinkedHashMap<String, Object>();
		values.put("v", 1);
		values.put("ts", key.timestamp());
		values.put("event", key.eventId().toString());
		values.put("app", key.applicationId().toString());
		values.put("env", key.environmentId().toString());
		values.put("level", key.level());
		values.put("from", range.from().toString());
		values.put("to", range.to().toString());
		values.put("context", context);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(values));
	}

	public Cursor decode(String token) {
		try {
			if (token.isEmpty() || token.length() > 8192)
				throw new IllegalArgumentException();
			byte[] bytes = Base64.getUrlDecoder().decode(token);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token))
				throw new IllegalArgumentException();
			var n = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
			if (!n.isObject() || n.size() != 9 || !"1".equals(n.get("v").toString()) || !n.get("ts").isIntegralNumber())
				throw new IllegalArgumentException();
			long ts = Long.parseLong(n.get("ts").toString());
			String level = n.get("level").asString();
			if (!List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level))
				throw new IllegalArgumentException();
			var range = new TimeRange(Instant.parse(n.get("from").asString()), Instant.parse(n.get("to").asString()));
			if (ts < range.fromMillis() || ts >= range.toMillis())
				throw new IllegalArgumentException();
			String context = n.get("context").asString();
			if (!context.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException();
			var cursor = new Cursor(new Key(ts, uuid(n.get("event").asString()), uuid(n.get("app").asString()),
					uuid(n.get("env").asString()), level), range, context);
			if (!encode(cursor.key(), range, context).equals(token))
				throw new IllegalArgumentException();
			return cursor;
		} catch (Exception ex) {
			throw new AnalysisException(ErrorCode.INVALID_CURSOR);
		}
	}

	private UUID uuid(String s) {
		var id = UUID.fromString(s);
		if (!id.toString().equals(s))
			throw new IllegalArgumentException();
		return id;
	}
}
