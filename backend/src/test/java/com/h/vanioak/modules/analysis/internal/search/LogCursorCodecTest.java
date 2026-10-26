package com.h.vanioak.modules.analysis.internal.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.h.vanioak.modules.analysis.api.AnalysisException;
import com.h.vanioak.modules.analysis.api.AuthorizedScope;
import com.h.vanioak.modules.analysis.api.ErrorCode;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.SearchFilters;
import com.h.vanioak.modules.analysis.api.AnalysisFacade.TimeRange;

class LogCursorCodecTest {
	final LogCursorCodec codec = new LogCursorCodec(new ObjectMapper());
	final Instant t = Instant.parse("2026-10-26T00:00:00Z");
	final UUID app = UUID.randomUUID(), env = UUID.randomUUID(), event = UUID.randomUUID();

	@Test
	void roundTripIncludesAllFiveLogicalKeyFields() {
		var key = new LogCursorCodec.Key(t.toEpochMilli(), event, app, env, "ERROR");
		var range = new TimeRange(t.minusSeconds(1), t.plusSeconds(1));
		var cursor = codec.decode(codec.encode(key, range, "a".repeat(64)));
		assertEquals(key, cursor.key());
		assertEquals(range, cursor.range());
		assertNotEquals(key, new LogCursorCodec.Key(key.timestamp(), event, app, env, "INFO"));
	}

	@Test
	void rejectsMalformedOversizedAndOutOfWindowTokens() {
		for (String s : new String[] { "", "bad!", "x".repeat(8193),
				Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes()) })
			assertEquals(ErrorCode.INVALID_CURSOR,
					assertThrows(AnalysisException.class, () -> codec.decode(s)).getErrorCode());
		var range = new TimeRange(t, t.plusSeconds(1));
		assertThrows(AnalysisException.class,
				() -> codec.decode(
						codec.encode(new LogCursorCodec.Key(t.minusMillis(1).toEpochMilli(), event, app, env, "INFO"),
								range, "a".repeat(64))));
	}

	@Test
	void contextBindsCallerScopeFiltersAndWindowButIsNotAMac() {
		var range = new TimeRange(t.minusSeconds(1), t.plusSeconds(1));
		var scope = new AuthorizedScope(UUID.randomUUID(), Set.of(app), null, null);
		var f = new SearchFilters(null, null, null, null, "needle", null, 1);
		String hash = codec.fingerprint(scope, f, range);
		assertEquals(hash,
				codec.fingerprint(scope, new SearchFilters(null, null, null, null, "needle", "ignored", 100), range));
		assertNotEquals(hash,
				codec.fingerprint(new AuthorizedScope(UUID.randomUUID(), Set.of(app), null, null), f, range));
		assertNotEquals(hash,
				codec.fingerprint(scope, new SearchFilters(null, null, null, null, "other", null, 1), range));
		// The public hash is not an authenticity proof: another valid in-scope position
		// is decodable.
		assertEquals(app,
				codec.decode(codec.encode(new LogCursorCodec.Key(t.toEpochMilli(), UUID.randomUUID(), app, env, "WARN"),
						range, hash)).key().applicationId());
	}
}
