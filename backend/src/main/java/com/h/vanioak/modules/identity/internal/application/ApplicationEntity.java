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

import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@Column(nullable = false, unique = true, length = 100)
	private String name;
	@Column(columnDefinition = "text")
	private String description;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	ApplicationEntity(String name, String description) {
		this.name = name;
		this.description = description;
		this.status = Status.ACTIVE;
	}

	void update(String name, String description, Status status) {
		if (name != null) this.name = name;
		if (description != null) this.description = description;
		if (status != null) this.status = status;
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

