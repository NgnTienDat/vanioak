package com.h.vanioak.modules.alert.internal.engine;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, UUID> {
	@Query("select h from IncidentHistory h where h.incidentId=:id order by h.eventAt desc, h.id desc")
	List<IncidentHistory> recent(@Param("id") UUID id, Pageable page);
}
