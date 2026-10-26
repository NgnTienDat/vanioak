package com.h.vanioak.modules.identity.internal.access;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_application_access")
@IdClass(UserApplicationAccessId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserApplicationAccessEntity {
	@Id @Column(name = "user_id", nullable = false)
	private UUID userId;
	@Id @Column(name = "application_id", nullable = false)
	private UUID applicationId;
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;
}
