package com.h.vanioak.modules.identity.internal.refreshtoken;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshTokenEntity t where t.familyId = :familyId and t.parentTokenId is null")
	Optional<RefreshTokenEntity> findFamilyRootForUpdate(@Param("familyId") UUID familyId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshTokenEntity t where t.tokenId = :tokenId")
	Optional<RefreshTokenEntity> findByTokenIdForUpdate(@Param("tokenId") UUID tokenId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshTokenEntity t set t.revoked = true where t.familyId = :familyId")
	int revokeFamily(@Param("familyId") UUID familyId);
}
