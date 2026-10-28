package com.h.vanioak.modules.alert.internal.rule;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Create;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Update;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alert_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertRuleEntity {
	@Id @GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@Column(name = "environment_id", nullable = false, updatable = false)
	private UUID environmentId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false)
	private Level level;
	@Column(nullable = false) private int threshold;
	@Column(name = "window_seconds", nullable = false) private int windowSeconds;
	@Column(name = "cooldown_seconds", nullable = false) private int cooldownSeconds;
	@Column(name = "resolve_after_seconds", nullable = false) private int resolveAfterSeconds;
	@Column(nullable = false) private boolean enabled;
	@Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	AlertRuleEntity(UUID createdBy, Create command) {
		this.createdBy = createdBy;
		environmentId = command.environmentId();
		level = command.level();
		threshold = command.threshold();
		windowSeconds = command.windowSeconds();
		cooldownSeconds = command.cooldownSeconds();
		resolveAfterSeconds = command.resolveAfterSeconds();
		enabled = command.enabled();
	}

	void update(Update command) {
		if (command.threshold() != null) threshold = command.threshold();
		if (command.windowSeconds() != null) windowSeconds = command.windowSeconds();
		if (command.cooldownSeconds() != null) cooldownSeconds = command.cooldownSeconds();
		if (command.resolveAfterSeconds() != null) resolveAfterSeconds = command.resolveAfterSeconds();
		if (command.enabled() != null) enabled = command.enabled();
	}

	@PrePersist
	void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }

	@PreUpdate
	void onUpdate() { updatedAt = Instant.now(); }
}
