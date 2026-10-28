package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class AlertEventDecoderTest {
	static final UUID ID=UUID.randomUUID(),APP=UUID.randomUUID(),ENV=UUID.randomUUID();
	static Message message(String text) { return new Message(text.getBytes(StandardCharsets.UTF_8),new MessageProperties()); }
	static String valid() {
		return """
			{"event_id":"%s","event_type":"critical.log","occurred_at":"2026-10-27T01:00:05Z","producer":"processing","schema_version":1,
			"payload":{"event_id":"%s","application_id":"%s","environment_id":"%s","level":"ERROR","message":"failure\\n漢字",
			"timestamp":"2020-01-01T00:00:00Z","trace_id":"","received_at":"2026-10-27T01:00:00.123456789Z"}}
			""".formatted(ID,ID,APP,ENV);
	}
	@Test void criticalLevelsUseExactReceivedAtNotProducerOrRetryTime() {
		for(String level:List.of("ERROR","CRITICAL")) {
			var decoded=new AlertEventDecoder().decode(message(valid().replace("ERROR",level)));
			assertEquals(AlertEventDecoder.Kind.VALID,decoded.kind());assertEquals(ID,decoded.event().eventId());assertEquals(ENV,decoded.event().environmentId());
			assertEquals(level,decoded.event().level().name());assertEquals("failure\n漢字",decoded.event().message());
			assertEquals(Instant.parse("2026-10-27T01:00:00.123456789Z"),decoded.event().receivedAt());
		}
	}
	@Test void invalidIdentityFieldsTypesAndUnsupportedLevelsAreRejected() {
		for(String body:List.of(valid().replace("ERROR","INFO"),valid().replace(APP.toString(),"bad"),valid().replace("critical.log","processed.log"),
				valid().replace("\"processing\"","\"alert\""),valid().replace("\"schema_version\":1","\"schema_version\":1.0"),
				valid().replace("\"trace_id\":\"\"","\"trace_id\":null"),valid().replace("2020-01-01T00:00:00Z","bad"),
				valid().replace("\"payload\":{\"event_id\":\""+ID,"\"payload\":{\"event_id\":\""+UUID.randomUUID())))
			assertEquals(AlertEventDecoder.Kind.INVALID,new AlertEventDecoder().decode(message(body)).kind());
	}
	@Test void unsupportedVersionDoesNotInterpretUnknownPayloadAsVersionOne() {
		var root=(tools.jackson.databind.node.ObjectNode)AlertWire.mapper().readTree(valid());root.put("schema_version",2).putObject("payload").put("future-shape",true);
		assertEquals(AlertEventDecoder.Kind.UNSUPPORTED,new AlertEventDecoder().decode(message(root.toString())).kind());
		root.remove("payload");assertEquals(AlertEventDecoder.Kind.INVALID,new AlertEventDecoder().decode(message(root.toString())).kind());
	}
	@Test void malformedUtf8JsonDuplicatesTrailingAndNonObjectsRetainExactBytes() {
		for(byte[] body:List.of(new byte[]{(byte)0xff},"{".getBytes(StandardCharsets.UTF_8),"[]".getBytes(StandardCharsets.UTF_8),
				"{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8),(valid()+"{}").getBytes(StandardCharsets.UTF_8))) {
			var decoded=new AlertEventDecoder().decode(new Message(body,new MessageProperties()));
			assertEquals(AlertEventDecoder.Kind.INVALID,decoded.kind());assertNull(decoded.original());assertArrayEquals(body,decoded.raw());
		}
	}
	@Test void unknownFieldsAndArbitraryPrecisionArePreserved() {
		String number="9".repeat(100);var decoded=new AlertEventDecoder().decode(message(valid().replace("\"payload\":", "\"unknown\":{\"n\":"+number+",\"d\":0.12345678901234567890123456789},\"payload\":")));
		assertEquals(AlertEventDecoder.Kind.VALID,decoded.kind());assertEquals(number,decoded.original().get("unknown").get("n").bigIntegerValue().toString());
		assertEquals("0.12345678901234567890123456789",decoded.original().get("unknown").get("d").decimalValue().toPlainString());
	}
	@Test void inputAndParserCapacityAreNotMisclassifiedAsMalformed() {
		assertEquals(AlertEventDecoder.Kind.CAPACITY,new AlertEventDecoder().decode(new Message(new byte[AlertWire.INPUT_LIMIT+1],new MessageProperties())).kind());
		assertEquals(AlertEventDecoder.Kind.CAPACITY,new AlertEventDecoder().decode(message("[".repeat(65)+"0"+"]".repeat(65))).kind());
		assertEquals(AlertEventDecoder.Kind.CAPACITY,new AlertEventDecoder().decode(message("{\"number\":"+"1".repeat(4097)+"}")).kind());
	}
}
