package com.h.vanioak.modules.identity.internal.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.h.vanioak.modules.identity.api.UserFacade.Status;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

	boolean existsByUsername(String username);

	Optional<UserEntity> findByUsername(String username);

	@Query("""
			select u from UserEntity u where u.role = 'ENGINEER'
			and (:status is null or u.status = :status)
			and (:after is null or u.id > :after) order by u.id asc
			""")
	List<UserEntity> findEngineers(@Param("status") Status status, @Param("after") UUID after, Pageable pageable);
}
