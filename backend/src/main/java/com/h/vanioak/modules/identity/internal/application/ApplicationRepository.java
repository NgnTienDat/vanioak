package com.h.vanioak.modules.identity.internal.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {
	boolean existsByName(String name);

	@Query("""
			select a from ApplicationEntity a
			where (:status is null or a.status = :status)
			and (:after is null or a.id > :after) order by a.id asc
			""")
	List<ApplicationEntity> findApplications(@Param("status") Status status, @Param("after") UUID after, Pageable pageable);
}

