package com.h.vanioak.modules.identity.internal.apikey;

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
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.h.vanioak.modules.identity.api.ApiKeyFacade.Status;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ingestion_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionCredentialEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;
	@Column(name = "environment_id", nullable = false)
	private UUID environmentId;
	@Column(name = "key_prefix", nullable = false, length = 16)
	private String keyPrefix;
	@Column(name = "key_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String keyHash;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;
	@Column(name = "expires_at")
	private Instant expiresAt;
	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	@Column(name = "revoked_at")
	private Instant revokedAt;

	IngestionCredentialEntity(UUID environmentId, String keyPrefix, String keyHash, UUID createdBy, Instant expiresAt) {
		this.environmentId = environmentId;
		this.keyPrefix = keyPrefix;
		this.keyHash = keyHash;
		this.createdBy = createdBy;
		this.expiresAt = expiresAt;
		this.status = Status.ACTIVE;
	}

	void revoke() {
		if (status != Status.REVOKED) {
			status = Status.REVOKED;
			revokedAt = Instant.now();
		}
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}

