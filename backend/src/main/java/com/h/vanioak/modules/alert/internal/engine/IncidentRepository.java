package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {
	@Query("select i from IncidentEntity i where i.environmentId in :environments "
			+ "and (:status is null or i.status=:status) and (:level is null or i.level=:level) "
			+ "and (cast(:lowerBound as Instant) is null or i.lastSeenAt>=:lowerBound) and (cast(:upperBound as Instant) is null or i.lastSeenAt<:upperBound) "
			+ "and (cast(:afterTime as Instant) is null or i.lastSeenAt<:afterTime or (i.lastSeenAt=:afterTime and i.id<:afterId)) "
			+ "order by i.lastSeenAt desc, i.id desc")
	List<IncidentEntity> readPage(@Param("environments") Set<UUID> environments, @Param("status") IncidentEntity.Status status,
			@Param("level") Level level, @Param("lowerBound") Instant from, @Param("upperBound") Instant to,
			@Param("afterTime") Instant afterTime, @Param("afterId") UUID afterId, Pageable page);

	@Query("select i from IncidentEntity i where i.id=:id and i.environmentId in :environments")
	Optional<IncidentEntity> readDetail(@Param("id") UUID id, @Param("environments") Set<UUID> environments);
	Optional<IncidentEntity> findByEnvironmentIdAndFingerprintAndStatus(UUID env, String fingerprint, IncidentEntity.Status status);

	@Query(value = "select i.* from incidents i join alert_rules r on r.id=i.alert_rule_id "
			+ "where i.status='OPEN' and i.last_seen_at + r.resolve_after_seconds * interval '1 second' <= :now "
			+ "order by i.last_seen_at, i.id", nativeQuery = true)
	List<IncidentEntity> due(@Param("now") Instant now, Pageable page);
}
