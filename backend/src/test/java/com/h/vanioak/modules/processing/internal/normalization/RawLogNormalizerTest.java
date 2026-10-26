package com.h.vanioak.modules.processing.internal.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RawLogNormalizerTest {
	private static final UUID BATCH_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID REQUEST_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
	private static final UUID APPLICATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID ENVIRONMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final Instant TIMESTAMP = Instant.parse("2026-10-10T01:02:03.123456789Z");
	private static final Instant RECEIVED_AT = Instant.parse("2026-10-10T01:02:04.987654321Z");
	private static final Instant PROCESSED_AT = Instant.parse("2026-10-10T01:02:05.111222333Z");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final RawLogNormalizer normalizer = new RawLogNormalizer(mapper, Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));

	@Test
	void normalizesSingleAndMultipleLogsFromTheExistingIngestionContract() {
		for (String[] levels : List.of(new String[] {"INFO"}, new String[] {"INFO", "WARN", "ERROR", "CRITICAL"})) {
			var event = event(levels);
			var result = normalizer.normalize(mapper.writeValueAsBytes(event));
			assertEquals(levels.length, result.size());
			for (int index = 0; index < levels.length; index++) {
				var input = event.payload().logs().get(index);
				assertEquals(new NormalizedLog(input.eventId(), APPLICATION_ID, ENVIRONMENT_ID, input.hostIp(),
						input.level(), input.message(), TIMESTAMP, input.traceId(), "{}", RECEIVED_AT, PROCESSED_AT),
						result.get(index));
				assertNotEquals(event.eventId(), result.get(index).eventId());
			}
		}
	}

	@Test
	void acceptsAThousandLogsWithoutConfusingTheStorageFlushSizeWithTheRawContract() {
		var event = event(IntStream.range(0, 1000).mapToObj(index -> "INFO").toArray(String[]::new));
		assertEquals(1000, normalizer.normalize(mapper.writeValueAsBytes(event)).size());
	}

	@Test
	void rejectsMissingNullAndWrongTypesForEveryRequiredField() {
		String[][] fields = {
				{"event_id", "event_type", "occurred_at", "producer", "schema_version", "payload"},
				{"request_id", "application_id", "environment_id", "received_at", "logs"},
				{"event_id", "host_ip", "level", "message", "timestamp", "trace_id"}
		};
		for (int depth = 0; depth < fields.length; depth++) {
			for (String field : fields[depth]) {
				var missing = tree();
				parent(missing, depth).remove(field);
				invalid(missing);
				for (JsonNode wrong : List.of(mapper.nullNode(), mapper.valueToTree(7), mapper.valueToTree(true),
						mapper.createObjectNode(), mapper.createArrayNode())) {
					var input = tree();
					parent(input, depth).set(field, wrong);
					invalid(input);
				}
			}
		}
	}

	@Test
	void rejectsMalformedNonObjectAndMultipleRootDocuments() {
		invalid((byte[]) null);
		for (String body : List.of("", " ", "null", "[]", "123", "true", "\"text\"", "{", "{broken}",
				mapper.writeValueAsString(event("INFO")) + " {}")) {
			invalid(body.getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	void parserConstraintFailuresUseTheSafeInvalidBatchError() {
		String input = mapper.writeValueAsString(event("INFO"));
		invalid(input.replace("\"schema_version\":1", "\"schema_version\":" + "9".repeat(2000))
				.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void rejectsEmptyLogArraysAndNonObjectItems() {
		var empty = tree();
		payload(empty).set("logs", mapper.createArrayNode());
		invalid(empty);
		for (JsonNode item : List.of(mapper.nullNode(), mapper.valueToTree("log"), mapper.valueToTree(1),
				mapper.valueToTree(false), mapper.createArrayNode())) {
			var input = tree();
			((ArrayNode) payload(input).get("logs")).set(0, item);
			invalid(input);
		}
	}

	@Test
	void rejectsUnsupportedEventTypesAndNonIntegerOrUnsupportedVersions() {
		for (String type : List.of("processed.log", "critical.log", "RAW.LOG", " raw.log", "")) {
			var input = tree();
			input.put("event_type", type);
			invalid(input);
		}
		for (JsonNode version : List.<JsonNode>of(mapper.valueToTree("1"), mapper.valueToTree(1.0), mapper.valueToTree(1.5),
				mapper.valueToTree(0), mapper.valueToTree(2), mapper.valueToTree(new BigInteger("4294967297")))) {
			var input = tree();
			input.set("schema_version", version);
			invalid(input);
		}
	}

	@Test
	void rejectsUnsupportedLevelsWithoutRepairingThem() {
		for (String level : List.of("DEBUG", "error", "INFO ", " WARN", "", "FATAL")) {
			var input = tree();
			log(input).put("level", level);
			invalid(input);
		}
	}

	@Test
	void rejectsMalformedUuidValuesInTheEnvelopePayloadAndLogs() {
		String[][] fields = {{"event_id"}, {"request_id", "application_id", "environment_id"}, {"event_id"}};
		for (int depth = 0; depth < fields.length; depth++) {
			for (String field : fields[depth]) {
				for (String id : List.of("", "not-a-uuid", "1-1-1-1-1", "+0000001-0000-0000-0000-000000000001")) {
					var input = tree();
					parent(input, depth).put(field, id);
					invalid(input);
				}
			}
		}
	}

	@Test
	void rejectsInvalidTimestampsAtEveryLocation() {
		String[] fields = {"occurred_at", "received_at", "timestamp"};
		for (int depth = 0; depth < fields.length; depth++) {
			for (String time : List.of("", "yesterday", "2026-10-10", "2026-10-10T01:02:03",
					"2026-02-30T01:02:03Z", "2026-10-10T25:02:03Z")) {
				var input = tree();
				parent(input, depth).put(fields[depth], time);
				invalid(input);
			}
		}
	}

	@Test
	void normalizesOffsetsWithoutLosingSourceTimestampPrecisionOrMixingTimeFields() {
		var input = tree();
		input.put("occurred_at", "2026-10-10T01:00:00Z");
		payload(input).put("received_at", "2026-10-10T08:02:04.987654321+07:00");
		log(input).put("timestamp", "2026-10-10T08:02:03.123456789+07:00");
		var result = normalize(input).getFirst();
		assertEquals(TIMESTAMP, result.timestamp());
		assertEquals(RECEIVED_AT, result.receivedAt());
		assertEquals(PROCESSED_AT, result.processedAt());
		assertEquals(normalizer.normalize(mapper.writeValueAsBytes(event("INFO"))).getFirst(), result);
	}

	@Test
	void preservesScopeAndLogIdentityDespiteConflictingExtraFieldsAndMetadata() {
		var input = tree();
		var other = UUID.randomUUID();
		log(input).put("application_id", other.toString());
		log(input).put("environment_id", other.toString());
		log(input).set("metadata", mapper.valueToTree(Map.of("application_id", other, "environment_id", other,
				"event_id", BATCH_ID)));
		var result = normalize(input).getFirst();
		assertEquals(APPLICATION_ID, result.applicationId());
		assertEquals(ENVIRONMENT_ID, result.environmentId());
		assertEquals(new UUID(0, 1), result.eventId());
	}

	@Test
	void preservesNestedMetadataAndExactJsonNumbers() {
		var input = tree();
		var metadata = mapper.createObjectNode();
		metadata.set("nested", mapper.readTree("{\"items\":[null,true,3,{\"text\":\"Tiếng Việt \\n\"}]}"));
		metadata.put("decimal", new BigDecimal("123456789.123456789123456789"));
		metadata.put("integer", new BigInteger("123456789123456789123456789123456789"));
		log(input).set("metadata", metadata);
		String json = normalize(input).getFirst().metadataJson();
		var restored = mapper.readTree(json);
		assertEquals(metadata.get("nested"), restored.get("nested"));
		assertEquals(new BigDecimal("123456789.123456789123456789"),
				mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
						.readTree(json).get("decimal").decimalValue());
		assertEquals(metadata.get("integer"), restored.get("integer"));
	}

	@Test
	void defaultsAbsentMetadataToAnEmptyObjectAndRejectsNullOrNonObjects() {
		var input = tree();
		log(input).remove("metadata");
		assertEquals("{}", normalize(input).getFirst().metadataJson());
		for (JsonNode metadata : List.of(mapper.nullNode(), mapper.valueToTree("sensitive-data"),
				mapper.valueToTree(1), mapper.valueToTree(true), mapper.createArrayNode())) {
			var wrong = tree();
			log(wrong).set("metadata", metadata);
			invalid(wrong);
		}
	}

	@Test
	void preservesPermittedEmptyStringsAndDoesNotAddProducerOrIdEqualityRules() {
		var input = tree();
		input.put("producer", "another-producer");
		for (String field : List.of("host_ip", "message", "trace_id")) log(input).put(field, "");
		log(input).put("event_id", BATCH_ID.toString().toUpperCase(Locale.ROOT));
		var result = normalize(input).getFirst();
		assertEquals(BATCH_ID, result.eventId());
		assertEquals("", result.hostIp());
		assertEquals("", result.message());
		assertEquals("", result.traceId());
	}

	@Test
	void acceptsUppercaseCanonicalUuidStrings() {
		var input = tree();
		UUID id = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
		log(input).put("event_id", id.toString().toUpperCase(Locale.ROOT));
		assertEquals(id, normalize(input).getFirst().eventId());
	}

	@Test
	void invalidSecondLogRejectsTheWholeBatchWithoutPoisoningTheNextCall() {
		var input = (ObjectNode) mapper.valueToTree(event("INFO", "ERROR"));
		((ObjectNode) payload(input).get("logs").get(1)).remove("timestamp");
		invalid(input);
		assertEquals(1, normalize(tree()).size());
	}

	@Test
	void repeatedNormalizationPreservesIdentityScopeAndIngestionTime() {
		byte[] body = mapper.writeValueAsBytes(event("WARN", "CRITICAL"));
		var first = normalizer.normalize(body);
		var later = new RawLogNormalizer(mapper, Clock.fixed(PROCESSED_AT.plusSeconds(5), ZoneOffset.UTC));
		var second = later.normalize(body);
		for (int index = 0; index < first.size(); index++) {
			var log = first.get(index);
			assertEquals(new NormalizedLog(log.eventId(), log.applicationId(), log.environmentId(), log.hostIp(),
					log.level(), log.message(), log.timestamp(), log.traceId(), log.metadataJson(), log.receivedAt(),
					PROCESSED_AT.plusSeconds(5)), second.get(index));
		}
	}

	@Test
	void unexpectedClockFailureIsNotClassifiedAsInvalidData() {
		Clock clock = mock(Clock.class);
		var failure = new IllegalStateException("Clock failure");
		when(clock.instant()).thenThrow(failure);
		var target = new RawLogNormalizer(mapper, clock);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> target.normalize(mapper.writeValueAsBytes(event("INFO")))));
	}

	private RawLogBatchEvent event(String... levels) {
		var logs = IntStream.range(0, levels.length).mapToObj(index -> new RawLogBatchEvent.Log(new UUID(0, index + 1),
				" source-host ", levels[index], " Log message " + index, TIMESTAMP, "trace-" + index, Map.of())).toList();
		return new RawLogBatchEvent(BATCH_ID, "raw.log", RECEIVED_AT, "ingestion", 1,
				new RawLogBatchEvent.Payload(REQUEST_ID, APPLICATION_ID, ENVIRONMENT_ID, RECEIVED_AT, logs));
	}

	private ObjectNode tree() {
		return (ObjectNode) mapper.valueToTree(event("INFO"));
	}

	private ObjectNode parent(ObjectNode event, int depth) {
		return switch (depth) {
			case 0 -> event;
			case 1 -> payload(event);
			case 2 -> log(event);
			default -> throw new IllegalArgumentException("Unknown test field location");
		};
	}

	private ObjectNode payload(ObjectNode event) {
		return (ObjectNode) event.get("payload");
	}

	private ObjectNode log(ObjectNode event) {
		return (ObjectNode) payload(event).get("logs").get(0);
	}

	private List<NormalizedLog> normalize(ObjectNode event) {
		return normalizer.normalize(mapper.writeValueAsBytes(event));
	}

	private void invalid(ObjectNode event) {
		invalid(mapper.writeValueAsBytes(event));
	}

	private void invalid(byte[] body) {
		var exception = assertThrows(ProcessingException.class, () -> normalizer.normalize(body));
		assertEquals(ErrorCode.INVALID_RAW_BATCH, exception.getErrorCode());
		assertEquals(HttpStatus.BAD_REQUEST, exception.getErrorCode().getStatus());
		assertEquals("Invalid raw log batch", exception.getMessage());
		assertNull(exception.getCause());
	}
}
