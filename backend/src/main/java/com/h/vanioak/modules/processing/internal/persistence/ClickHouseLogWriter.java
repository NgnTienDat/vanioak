package com.h.vanioak.modules.processing.internal.persistence;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.modules.processing.api.ErrorCode;
import com.h.vanioak.modules.processing.api.ProcessingException;
import com.h.vanioak.modules.processing.internal.normalization.NormalizedLog;

import tools.jackson.databind.ObjectMapper;

public class ClickHouseLogWriter {

	private static final String INSERT = "INSERT INTO logs (event_id, application_id, environment_id, host_ip, "
			+ "level, message, timestamp, trace_id, metadata_json, received_at, processed_at) FORMAT JSONEachRow\n";
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
			.withZone(ZoneOffset.UTC);
	private final ClickHouseConnection connection;
	private final ObjectMapper mapper;

	public ClickHouseLogWriter(ClickHouseConnection connection, ObjectMapper mapper) {
		this.connection = connection;
		this.mapper = mapper;
	}

	public void write(List<NormalizedLog> logs) {
		List<NormalizedLog> records = List.copyOf(logs);
		if (records.isEmpty() || records.size() > 1000) {
			throw new IllegalArgumentException("An insert requires 1 to 1000 normalized logs");
		}
		StringBuilder body = new StringBuilder(INSERT);
		for (NormalizedLog log : records) {
			var row = mapper.createObjectNode();
			row.put("event_id", log.eventId().toString());
			row.put("application_id", log.applicationId().toString());
			row.put("environment_id", log.environmentId().toString());
			row.put("host_ip", log.hostIp());
			row.put("level", log.level());
			row.put("message", log.message());
			row.put("timestamp", TIMESTAMP.format(log.timestamp()));
			row.put("trace_id", log.traceId());
			row.put("metadata_json", log.metadataJson());
			row.put("received_at", TIMESTAMP.format(log.receivedAt()));
			row.put("processed_at", TIMESTAMP.format(log.processedAt()));
			body.append(mapper.writeValueAsString(row)).append('\n');
		}
		try {
			connection.execute(body.toString());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE);
		} catch (IOException exception) {
			throw new ProcessingException(ErrorCode.PERSISTENCE_UNAVAILABLE);
		}
	}
}
