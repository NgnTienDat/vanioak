package com.h.vanioak.modules.processing.internal.normalization;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;

import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.exc.MismatchedInputException;

public class RawLogNormalizer {
	private final ObjectMapper mapper;
	private final ObjectReader reader;
	private final Clock clock;

	public RawLogNormalizer(ObjectMapper mapper) {
		this(mapper, Clock.systemUTC());
	}

	RawLogNormalizer(ObjectMapper mapper, Clock clock) {
		this.mapper = mapper;
		this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
		this.clock = clock;
	}

	public List<NormalizedLog> normalize(byte[] body) {
		if (body == null) throw invalid();
		JsonNode event;
		try {
			event = reader.readTree(body);
		} catch (StreamReadException | StreamConstraintsException | MismatchedInputException ex) {
			throw invalid();
		}
		object(event);
		uuid(event, "event_id");
		if (!"raw.log".equals(text(event, "event_type"))) throw invalid();
		timestamp(event, "occurred_at");
		text(event, "producer");
		JsonNode version = event.get("schema_version");
		if (version == null || !version.isIntegralNumber() || !BigInteger.ONE.equals(version.bigIntegerValue()))
			throw invalid();
		JsonNode payload = object(event.get("payload"));
		uuid(payload, "request_id");
		UUID applicationId = uuid(payload, "application_id");
		UUID environmentId = uuid(payload, "environment_id");
		Instant receivedAt = timestamp(payload, "received_at");
		JsonNode logs = payload.get("logs");
		if (logs == null || !logs.isArray() || logs.isEmpty()) throw invalid();

		Instant processedAt = clock.instant();
		var normalized = new ArrayList<NormalizedLog>(logs.size());
		for (JsonNode log : logs) {
			object(log);
			String level = text(log, "level");
			if (!List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level)) throw invalid();
			JsonNode metadata = log.get("metadata");
			String metadataJson = metadata == null ? "{}" : mapper.writeValueAsString(object(metadata));
			normalized.add(new NormalizedLog(uuid(log, "event_id"), applicationId, environmentId,
					text(log, "host_ip"), level, text(log, "message"), timestamp(log, "timestamp"),
					text(log, "trace_id"), metadataJson, receivedAt, processedAt));
		}
		return List.copyOf(normalized);
	}

	private JsonNode object(JsonNode value) {
		if (value == null || !value.isObject()) throw invalid();
		return value;
	}

	private String text(JsonNode parent, String field) {
		JsonNode value = parent.get(field);
		if (value == null || !value.isString()) throw invalid();
		return value.asString();
	}

	private UUID uuid(JsonNode parent, String field) {
		String value = text(parent, field);
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw invalid();
			return id;
		} catch (IllegalArgumentException ex) {
			throw invalid();
		}
	}

	private Instant timestamp(JsonNode parent, String field) {
		try {
			return Instant.parse(text(parent, field));
		} catch (DateTimeParseException ex) {
			throw invalid();
		}
	}

	private ProcessingException invalid() {
		return new ProcessingException(ErrorCode.INVALID_RAW_BATCH);
	}
}
