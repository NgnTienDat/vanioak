package com.h.vanioak.modules.realtime.internal.consumer;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.core.Message;

import com.h.vanioak.modules.realtime.api.RealtimeFacade.LogEvent;

import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class RealtimeEventDecoder {
	private final JsonMapper mapper = RealtimeJson.reader();

	public enum Kind { VALID, INVALID, UNSUPPORTED, CAPACITY }
	public record Decoded(Kind kind, LogEvent event, JsonNode original, byte[] raw) {
		public String reason() {
			return kind == Kind.UNSUPPORTED ? "REALTIME_UNSUPPORTED_SCHEMA_VERSION" : "REALTIME_INVALID_PROCESSED_EVENT";
		}
	}

	public Decoded decode(Message message) {
		byte[] source = message.getBody();
		if (source.length > RealtimeJson.INPUT_LIMIT) return new Decoded(Kind.CAPACITY, null, null, null);
		byte[] raw = source.clone();
		JsonNode original;
		try {
			String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
			original = mapper.readTree(utf8);
		} catch (StreamConstraintsException | InputCoercionException | NumberFormatException ex) {
			return new Decoded(Kind.CAPACITY, null, null, null);
		} catch (CharacterCodingException | StreamReadException | MismatchedInputException ex) {
			return new Decoded(Kind.INVALID, null, null, raw);
		} catch (RuntimeException ex) {
			return new Decoded(Kind.CAPACITY, null, null, null);
		}
		if (original == null || !original.isObject()) return new Decoded(Kind.INVALID, null, null, raw);
		try {
			UUID id = uuid(original, "event_id");
			if (!"processed.log".equals(text(original, "event_type")) || !"processing".equals(text(original, "producer")))
				throw new IllegalArgumentException();
			Instant.parse(text(original, "occurred_at"));
			JsonNode version = original.get("schema_version");
			if (version == null || !version.isIntegralNumber()) throw new IllegalArgumentException();
			if (!BigInteger.ONE.equals(version.bigIntegerValue())) return new Decoded(Kind.UNSUPPORTED, null, original, raw);
			JsonNode payload = original.get("payload");
			if (payload == null || !payload.isObject() || !id.equals(uuid(payload, "event_id"))) throw new IllegalArgumentException();
			String level = text(payload, "level");
			if (!List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level)) throw new IllegalArgumentException();
			Instant.parse(text(payload, "received_at"));
			Instant.parse(text(payload, "processed_at"));
			JsonNode metadata = payload.get("metadata");
			if (metadata == null || !metadata.isObject()) throw new IllegalArgumentException();
			Map<String, Object> fields = new LinkedHashMap<>();
			metadata.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
			LogEvent event = new LogEvent(id, uuid(payload, "application_id"), uuid(payload, "environment_id"),
					text(payload, "host_ip"), level, text(payload, "message"), Instant.parse(text(payload, "timestamp")),
					text(payload, "trace_id"), fields);
			return new Decoded(Kind.VALID, event, original, raw);
		} catch (RuntimeException ex) {
			return new Decoded(Kind.INVALID, null, original, raw);
		}
	}

	static UUID canonical(String value) {
		try {
			UUID id = UUID.fromString(value);
			return id.toString().equalsIgnoreCase(value) ? id : null;
		} catch (RuntimeException ex) { return null; }
	}
	private static UUID uuid(JsonNode parent, String key) {
		UUID id = canonical(text(parent, key));
		if (id == null) throw new IllegalArgumentException();
		return id;
	}
	private static String text(JsonNode parent, String key) {
		JsonNode value = parent.get(key);
		if (value == null || !value.isString()) throw new IllegalArgumentException();
		return value.asString();
	}
}
