package com.h.vanioak.modules.alert.internal.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.internal.rule.AlertRuleRepository;

class AlertEngineInfrastructureTest {
	@Test void auditMigrationAndCanonicalSchemaKeepNullableLegacyAndRestrictiveActorReference() throws Exception {
		var root=java.nio.file.Path.of(System.getProperty("user.dir"));
		while(!java.nio.file.Files.isRegularFile(root.resolve("docs/contracts/postgres-schema.sql"))) root=root.getParent();
		var ddl=java.nio.file.Files.readString(root.resolve("docs/contracts/postgres-schema.sql"));
		var migration=java.nio.file.Files.readString(root.resolve("backend/src/main/resources/db/migration/V3__incident_resolution_audit.sql"));
		for(var sql:List.of(ddl,migration)) {
			assertTrue(sql.contains("resolved_by_user_id") && sql.contains("REFERENCES users(id)"));
			assertTrue(sql.contains("resolution_source IS NULL AND resolved_by_user_id IS NULL"));
			assertTrue(sql.contains("status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_source IS NOT NULL"));
			assertTrue(sql.contains("resolution_source = 'MANUAL' AND resolved_by_user_id IS NOT NULL"));
			assertTrue(sql.contains("resolution_source = 'AUTOMATIC' AND resolved_by_user_id IS NULL"));
			assertTrue(sql.replaceAll("\\s+"," ").contains("ON incidents(environment_id, fingerprint, resolved_at DESC) WHERE resolution_source = 'MANUAL'"));
		}
		assertFalse(migration.matches("(?is).*\\b(UPDATE|DELETE|DROP|CASCADE)\\b.*"));
		assertTrue(ddl.contains("CREATE UNIQUE INDEX ux_incidents_open_fingerprint"));
		assertEquals("resolution_source",IncidentEntity.class.getDeclaredField("resolutionSource").getAnnotation(jakarta.persistence.Column.class).name());
		var metadata=IncidentHistory.class.getDeclaredField("metadata").getAnnotation(jakarta.persistence.Column.class);
		assertTrue(metadata.insertable());assertFalse(metadata.updatable());
	}
	@Test void fingerprintMappingsMatchExistingChar64Schema() throws Exception {
		for(var type:List.of(IncidentEntity.class,AlertEventReceipt.class)) {
			var field=type.getDeclaredField("fingerprint");
			assertEquals(org.hibernate.type.SqlTypes.CHAR,field.getAnnotation(org.hibernate.annotations.JdbcTypeCode.class).value());
			assertEquals("char(64)",field.getAnnotation(jakarta.persistence.Column.class).columnDefinition());
		}
	}
	@Test void advisoryLocksRequireTransactionAndSeparateEventFromScopeNamespaces() {
		var em=mock(EntityManager.class); var query=mock(Query.class); var lock=new AlertScopeLock(em);
		when(em.createNativeQuery(anyString())).thenReturn(query); when(query.setParameter(anyString(),any())).thenReturn(query);
		assertThrows(IllegalStateException.class,() -> lock.event(UUID.randomUUID())); verifyNoInteractions(em);
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			UUID env=UUID.randomUUID(); lock.event(env); lock.scope(env,"fingerprint");
			verify(em,times(2)).createNativeQuery("select 1 from pg_advisory_xact_lock(:namespace, :key)");
			verify(query).setParameter("namespace",0x56414C45); verify(query).setParameter("namespace",0x56414C53);
			verify(query,times(2)).getSingleResult();
		} finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
	}

	@Test void ruleIsReadLockedAndDueQueryUsesPerRuleDeadlineNotAnOldestRowPrefix() throws Exception {
		assertEquals(LockModeType.PESSIMISTIC_READ,AlertRuleRepository.class.getMethod("forEvaluation",UUID.class,Level.class).getAnnotation(Lock.class).value());
		var query=IncidentRepository.class.getMethod("due",Instant.class,org.springframework.data.domain.Pageable.class)
				.getAnnotation(org.springframework.data.jpa.repository.Query.class);
		assertTrue(query.nativeQuery()); assertTrue(query.value().contains("resolve_after_seconds * interval '1 second' <= :now"));
		assertTrue(query.value().indexOf("where")<query.value().indexOf("order by"));
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	@Test void redisReconciliationIsAtomicExactScoredAndNamespaced() {
		var redis=mock(StringRedisTemplate.class);var frequency=new RedisAlertFrequency(redis);
		when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(2L);
		UUID env=UUID.randomUUID(),rule=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
		Instant time=Instant.parse("2026-10-27T00:00:00.123456Z");
		var rows=List.of(new AlertEventReceipt(a,env,"fp",rule,time,time),new AlertEventReceipt(b,env,"fp",rule,time,time));
		frequency.reconcile(env,"fp",rule,60,rows);
		var script=ArgumentCaptor.forClass(RedisScript.class);var keys=ArgumentCaptor.forClass(List.class);var args=ArgumentCaptor.forClass(Object[].class);
		verify(redis).execute(script.capture(),keys.capture(),args.capture());
		assertEquals(List.of("vanioak:alert:frequency:"+env+":fp:"+rule),keys.getValue());
		assertArrayEquals(new Object[]{"60",Long.toString(RedisAlertFrequency.micros(time)),a.toString(),Long.toString(RedisAlertFrequency.micros(time)),b.toString()},args.getValue());
		String lua=script.getValue().getScriptAsString();assertTrue(lua.indexOf("'DEL'")<lua.indexOf("'ZADD'"));
		assertTrue(lua.contains("'EXPIRE'"));assertTrue(lua.contains("'ZCARD'"));
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	@Test void redisFailureOrUnexpectedCountFailsClosed() {
		var redis=mock(StringRedisTemplate.class);var frequency=new RedisAlertFrequency(redis);
		when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(null,1L).thenThrow(new IllegalStateException("connection"));
		for(int i=0;i<2;i++)assertThrows(AlertException.class,() -> frequency.reconcile(UUID.randomUUID(),"fp",UUID.randomUUID(),60,List.of()));
		assertThrows(IllegalStateException.class,() -> frequency.reconcile(UUID.randomUUID(),"fp",UUID.randomUUID(),60,List.of()));
	}

	@Test void resolverHasNoDefaultActivation() {
		new ApplicationContextRunner().withUserConfiguration(AlertResolutionScheduler.class)
				.withBean(AlertEngineFacade.class,() -> mock(AlertEngineFacade.class))
				.run(context -> assertTrue(context.getBeansOfType(AlertResolutionScheduler.class).isEmpty()));
	}

	@Test void resolverSchedulesOneBoundedJobSurvivesFailureAndStopsOwnedWorker() throws Exception {
		var engine=mock(AlertEngineFacade.class);var worker=mock(ScheduledExecutorService.class);
		when(engine.resolveInactive(100)).thenThrow(new IllegalStateException("private")).thenReturn(2);
		when(worker.awaitTermination(5,TimeUnit.SECONDS)).thenReturn(true);
		var resolver=new AlertResolutionScheduler(engine,Duration.ofSeconds(1),worker);resolver.start();
		var task=ArgumentCaptor.forClass(Runnable.class);
		verify(worker).scheduleWithFixedDelay(task.capture(),eq(1000L),eq(1000L),eq(TimeUnit.MILLISECONDS));
		task.getValue().run();task.getValue().run();verify(engine,times(2)).resolveInactive(100);
		resolver.close();verify(worker).shutdownNow();verify(worker).awaitTermination(5,TimeUnit.SECONDS);
	}

	@Test void resolverRejectsInvalidIntervalBeforeScheduling() {
		var worker=mock(ScheduledExecutorService.class);
		assertThrows(IllegalArgumentException.class,() -> new AlertResolutionScheduler(mock(AlertEngineFacade.class),Duration.ZERO,worker));
		verifyNoInteractions(worker);
	}
}
