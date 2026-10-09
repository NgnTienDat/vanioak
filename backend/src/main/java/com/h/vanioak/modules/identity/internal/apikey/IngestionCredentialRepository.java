package com.h.vanioak.modules.identity.internal.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IngestionCredentialRepository extends JpaRepository<IngestionCredentialEntity, UUID> {
	@Transactional(readOnly = true)
	Optional<IngestionCredentialEntity> findByKeyHash(String keyHash);
	@Query("""
			select c from IngestionCredentialEntity c where c.environmentId = :environmentId
			and (:after is null or c.id > :after) order by c.id asc
			""")
	List<IngestionCredentialEntity> findCredentials(@Param("environmentId") UUID environmentId,
			@Param("after") UUID after, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from IngestionCredentialEntity c where c.id = :id")
	Optional<IngestionCredentialEntity> findByIdForUpdate(@Param("id") UUID id);
}

