package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.UUID;
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
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentResolutionFacade.Source;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "incidents")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentEntity {
	public enum Status { OPEN, RESOLVED }
	@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
	@Column(name = "alert_rule_id", nullable = false, updatable = false) private UUID alertRuleId;
	@Column(name = "environment_id", nullable = false, updatable = false) private UUID environmentId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Level level;
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 64, updatable = false, columnDefinition = "char(64)") private String fingerprint;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
	@Column(nullable = false, columnDefinition = "text", updatable = false) private String message;
	@Column(name = "error_count", nullable = false) private long errorCount;
	@Column(name = "first_seen_at", nullable = false) private Instant firstSeenAt;
	@Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
	@Column(name = "resolved_at") private Instant resolvedAt;
	@Enumerated(EnumType.STRING) @Column(name = "resolution_source", length = 20) private Source resolutionSource;
	@Column(name = "resolved_by_user_id") private UUID resolvedByUserId;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	IncidentEntity(UUID rule, UUID env, Level level, String fingerprint, String message,
			long count, Instant first, Instant last, Instant now) {
		alertRuleId = rule; environmentId = env; this.level = level; this.fingerprint = fingerprint;
		this.message = message; errorCount = count; firstSeenAt = first; lastSeenAt = last;
		status = Status.OPEN; createdAt = now; updatedAt = now;
	}

	void record(Instant receivedAt, Instant now) {
		errorCount = Math.addExact(errorCount, 1);
		if (receivedAt.isBefore(firstSeenAt)) firstSeenAt = receivedAt;
		if (receivedAt.isAfter(lastSeenAt)) lastSeenAt = receivedAt;
		updatedAt = now;
	}

	void resolve(Instant now) { resolve(now, Source.AUTOMATIC, null); }

	void resolveManually(Instant now, UUID actor) { resolve(now, Source.MANUAL, java.util.Objects.requireNonNull(actor)); }

	private void resolve(Instant now, Source source, UUID actor) {
		if (status != Status.OPEN) return;
		status = Status.RESOLVED; resolvedAt = now; updatedAt = now;
		resolutionSource = source; resolvedByUserId = actor;
	}
}
