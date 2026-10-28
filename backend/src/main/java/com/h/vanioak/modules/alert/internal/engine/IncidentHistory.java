package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "incident_events")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentHistory {
	public enum Type { OPENED, COUNT_UPDATED, RESOLVED }
	@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
	@Column(name = "incident_id", nullable = false, updatable = false) private UUID incidentId;
	@Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 30, updatable = false) private Type eventType;
	@Column(name = "event_at", nullable = false, updatable = false) private Instant eventAt;
	@Column(name = "error_count", updatable = false) private Long errorCount;
	@Column(columnDefinition = "text", updatable = false) private String message;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", insertable = false, updatable = false) private Map<String,Object> metadata;

	IncidentHistory(IncidentEntity incident, Type type, Instant now) {
		incidentId = incident.getId(); eventType = type; eventAt = now; errorCount = incident.getErrorCount();
		message = type == Type.OPENED ? incident.getMessage() : null;
	}
}
