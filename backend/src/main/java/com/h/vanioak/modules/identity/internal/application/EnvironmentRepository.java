package com.h.vanioak.modules.identity.internal.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, UUID> {
	List<EnvironmentEntity> findByApplicationIdIn(Collection<UUID> applicationIds);
}

