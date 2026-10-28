package com.h.vanioak.modules.alert.internal.rule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, UUID> {
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select r from AlertRuleEntity r where r.environmentId=:env and r.level=:level")
	Optional<AlertRuleEntity> forEvaluation(@Param("env") UUID env, @Param("level") Level level);
	@Query("select r from AlertRuleEntity r where (:after is null or r.id > :after) order by r.id")
	List<AlertRuleEntity> findRules(@Param("after") UUID after, Pageable page);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from AlertRuleEntity r where r.id = :id")
	Optional<AlertRuleEntity> findLocked(@Param("id") UUID id);
}
