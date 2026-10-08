package com.h.vanioak.modules.identity.internal.refreshtoken;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenEntity {

	@Id
	@Column(name = "token_id", nullable = false, updatable = false)
	private UUID tokenId;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "parent_token_id", unique = true, updatable = false)
	private UUID parentTokenId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Setter
	@Column(nullable = false)
	private boolean used;

	@Setter
	@Column(nullable = false)
	private boolean revoked;

	@Generated
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	public RefreshTokenEntity(UUID tokenId, UUID familyId, UUID parentTokenId, UUID userId, Instant expiresAt) {
		this.tokenId = tokenId;
		this.familyId = familyId;
		this.parentTokenId = parentTokenId;
		this.userId = userId;
		this.expiresAt = expiresAt;
	}
}
