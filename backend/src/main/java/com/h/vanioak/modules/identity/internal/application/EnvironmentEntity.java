package com.h.vanioak.modules.identity.internal.application;

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
import jakarta.persistence.UniqueConstraint;

import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "environments", uniqueConstraints = @UniqueConstraint(
		name = "uq_environments_application_name", columnNames = {"application_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnvironmentEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@Column(name = "application_id", nullable = false)
	private UUID applicationId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private EnvironmentName name;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	EnvironmentEntity(UUID applicationId, EnvironmentName name) {
		this.applicationId = applicationId;
		this.name = name;
		this.status = Status.ACTIVE;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}

