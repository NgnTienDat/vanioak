package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertReceiptRepository extends JpaRepository<AlertEventReceipt, UUID> {
	@Query("select max(r.receivedAt) from AlertEventReceipt r where r.environmentId=:env and r.fingerprint=:fingerprint and r.alertRuleId=:rule")
	Instant latest(@Param("env") UUID env, @Param("fingerprint") String fingerprint, @Param("rule") UUID rule);

	@Query("select r from AlertEventReceipt r where r.environmentId=:env and r.fingerprint=:fingerprint and r.alertRuleId=:rule and r.receivedAt>:from and r.receivedAt<=:to")
	List<AlertEventReceipt> window(@Param("env") UUID env, @Param("fingerprint") String fingerprint,
			@Param("rule") UUID rule, @Param("from") Instant from, @Param("to") Instant to);
}
