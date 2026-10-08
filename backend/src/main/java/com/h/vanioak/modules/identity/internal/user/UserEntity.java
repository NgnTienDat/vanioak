package com.h.vanioak.modules.identity.internal.user;

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

import com.h.vanioak.modules.identity.api.UserFacade.Status;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true, length = 100)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 20)
	private String role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	UserEntity(String username, String passwordHash) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.role = "ENGINEER";
		this.status = Status.ACTIVE;
	}

	void update(String username, String passwordHash, Status status) {
		if (username != null) this.username = username;
		if (passwordHash != null) this.passwordHash = passwordHash;
		if (status != null) this.status = status;
	}

	static UserEntity initialAdmin(String username, String passwordHash) {
		var admin = new UserEntity(username, passwordHash);
		admin.role = "ADMIN";
		return admin;
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
