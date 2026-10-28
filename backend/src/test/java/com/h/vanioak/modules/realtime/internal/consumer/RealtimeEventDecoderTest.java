package com.h.vanioak.modules.realtime.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.node.ObjectNode;

class RealtimeEventDecoderTest {
	final RealtimeEventDecoder decoder = new RealtimeEventDecoder();
	static final String ID = "00000000-0000-0000-0000-000000000001";
	static ObjectNode event() {
		var mapper = RealtimeJson.reader();
		var root = mapper.createObjectNode();
		root.put("event_id", ID).put("event_type", "processed.log").put("producer", "processing")
				.put("schema_version", 1).put("occurred_at", "2026-10-26T00:00:01Z");
		var payload = root.putObject("payload");
		payload.put("event_id", ID).put("application_id", UUID.randomUUID().toString())
				.put("environment_id", UUID.randomUUID().toString()).put("host_ip", "host").put("level", "INFO")
				.put("message", "hello").put("timestamp", "2026-10-26T00:00:00Z").put("trace_id", "trace")
				.put("received_at", "2026-10-26T00:00:00Z").put("processed_at", "2026-10-26T00:00:01Z");
		payload.putObject("metadata").put("precise", new BigDecimal("0.12345678901234567890123456789"));
		return root;
	}
	static Message message(byte[] body) {
		var properties = new MessageProperties(); properties.setConsumerQueue("realtime.queue"); properties.setDeliveryTag(1);
		return new Message(body, properties);
	}
	static Message message(ObjectNode event) { return message(RealtimeJson.reader().writeValueAsBytes(event)); }

	@Test void validScopeIdentityAndPrecisionArePreserved() {
		var original = event(); original.put("unknown", "retained");
		var decoded = decoder.decode(message(original));
		assertEquals(RealtimeEventDecoder.Kind.VALID, decoded.kind());
		assertEquals(UUID.fromString(ID), decoded.event().eventId());
		assertEquals("retained", decoded.original().get("unknown").asString());
		assertEquals(new BigDecimal("0.12345678901234567890123456789"),
				((tools.jackson.databind.JsonNode) decoded.event().metadata().get("precise")).decimalValue());
	}

	@Test void completeEnvelopeChecksRejectWrongProducerScopeIdentityAndMetadata() {
		for (String key : new String[] {"producer", "event_id", "occurred_at", "event_type"}) {
			var source = event(); source.put(key, "invalid");
			assertEquals(RealtimeEventDecoder.Kind.INVALID, decoder.decode(message(source)).kind(), key);
		}
		for (String key : new String[] {"event_id", "application_id", "environment_id", "level", "timestamp", "received_at", "processed_at"}) {
			var source = event(); ((ObjectNode) source.get("payload")).put(key, "invalid");
			assertEquals(RealtimeEventDecoder.Kind.INVALID, decoder.decode(message(source)).kind(), key);
		}
		var source = event(); ((ObjectNode) source.get("payload")).putNull("metadata");
		assertEquals(RealtimeEventDecoder.Kind.INVALID, decoder.decode(message(source)).kind());
	}

	@Test void unsupportedIntegralVersionIsSeparateFromMalformedVersion() {
		var source = event(); source.put("schema_version", 2); source.remove("payload");
		assertEquals(RealtimeEventDecoder.Kind.UNSUPPORTED, decoder.decode(message(source)).kind());
		source.put("schema_version", "2");
		assertEquals(RealtimeEventDecoder.Kind.INVALID, decoder.decode(message(source)).kind());
	}

	@Test void malformedUtf8DuplicatesTrailingAndNonObjectsKeepExactBytes() {
		for (byte[] raw : new byte[][] {new byte[] {(byte) 0xff, 0}, "[]".getBytes(StandardCharsets.UTF_8),
				"{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8), "{} {}".getBytes(StandardCharsets.UTF_8),
				"{broken".getBytes(StandardCharsets.UTF_8)}) {
			var decoded = decoder.decode(message(raw));
			assertEquals(RealtimeEventDecoder.Kind.INVALID, decoded.kind());
			assertNull(decoded.original()); assertArrayEquals(raw, decoded.raw());
		}
	}

	@Test void inputAndParserLimitsAreCapacityNotMalformed() {
		assertEquals(RealtimeEventDecoder.Kind.CAPACITY, decoder.decode(message(new byte[RealtimeJson.INPUT_LIMIT + 1])).kind());
		String nested = "{\"x\":".repeat(65) + "0" + "}".repeat(65);
		assertEquals(RealtimeEventDecoder.Kind.CAPACITY, decoder.decode(message(nested.getBytes(StandardCharsets.UTF_8))).kind());
		String number = "{\"x\":" + "9".repeat(4097) + "}";
		assertEquals(RealtimeEventDecoder.Kind.CAPACITY, decoder.decode(message(number.getBytes(StandardCharsets.UTF_8))).kind());
		String exponent = "{\"x\":1e2147483648}";
		assertEquals(RealtimeEventDecoder.Kind.CAPACITY, decoder.decode(message(exponent.getBytes(StandardCharsets.UTF_8))).kind());
	}
}
