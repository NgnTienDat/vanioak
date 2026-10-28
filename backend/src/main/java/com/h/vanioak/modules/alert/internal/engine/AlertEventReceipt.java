package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "alert_event_receipts")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertEventReceipt {
	@Id @Column(name = "event_id") private UUID eventId;
	@Column(name = "environment_id", nullable = false, updatable = false) private UUID environmentId;
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 64, updatable = false, columnDefinition = "char(64)") private String fingerprint;
	@Column(name = "alert_rule_id", updatable = false) private UUID alertRuleId;
	@Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
	@Column(name = "incident_id") private UUID incidentId;
	@Column(name = "accounted_at", nullable = false, updatable = false) private Instant accountedAt;

	AlertEventReceipt(UUID eventId, UUID env, String fingerprint, UUID rule, Instant receivedAt, Instant now) {
		this.eventId = eventId; environmentId = env; this.fingerprint = fingerprint;
		alertRuleId = rule; this.receivedAt = receivedAt; accountedAt = now;
	}

	void associate(UUID incidentId) { this.incidentId = incidentId; }
}
