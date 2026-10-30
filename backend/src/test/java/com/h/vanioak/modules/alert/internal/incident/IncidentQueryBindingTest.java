package com.h.vanioak.modules.alert.internal.incident;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.internal.engine.IncidentEntity;
import com.h.vanioak.modules.alert.internal.engine.IncidentRepository;

/** Real PostgreSQL-dialect SQL generation and binding, without a database or Spring context. */
class IncidentQueryBindingTest {
	static SessionFactory factory;
	static Connection connection;
	static PreparedStatement statement;
	static final AtomicReference<String> sql=new AtomicReference<>();
	static String hql;
	final UUID environment=UUID.fromString("a822d0a6-2b02-4f35-bcb3-ce9ca81613ca");
	final Instant time=Instant.parse("2026-10-27T13:04:35.947733Z");

	@BeforeAll static void setup() throws Exception {
		hql=IncidentRepository.class.getMethod("readPage",Set.class,IncidentEntity.Status.class,Level.class,Instant.class,Instant.class,
				Instant.class,UUID.class,Pageable.class).getAnnotation(Query.class).value();
		connection=mock(Connection.class);statement=mock(PreparedStatement.class);
		var result=mock(ResultSet.class);when(statement.executeQuery()).thenReturn(result);when(result.getStatement()).thenReturn(statement);
		when(connection.prepareStatement(anyString())).thenAnswer(call -> {sql.set(call.getArgument(0));return statement;});
		when(connection.prepareStatement(anyString(),anyInt(),anyInt())).thenAnswer(call -> {sql.set(call.getArgument(0));return statement;});
		when(connection.getAutoCommit()).thenReturn(true);
		var provider=mock(ConnectionProvider.class);when(provider.getConnection()).thenReturn(connection);
		var configuration=new Configuration().addAnnotatedClass(IncidentEntity.class);
		configuration.setProperty("hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect");
		configuration.setProperty("hibernate.boot.allow_jdbc_metadata_access","false");
		configuration.setProperty("hibernate.hbm2ddl.auto","none");
		configuration.getProperties().put("hibernate.connection.provider_class",provider);
		factory=configuration.buildSessionFactory();
	}
	@AfterAll static void close() {if(factory!=null)factory.close();}
	@BeforeEach void clear() {clearInvocations(statement,connection);sql.set(null);}

	String execute(String query,Set<UUID> environments,IncidentEntity.Status status,Level level,Instant from,Instant to,Instant after,UUID afterId) {
		try(var session=factory.openSession()) {
			assertTrue(session.createSelectionQuery(query,IncidentEntity.class).setParameter("environments",environments)
					.setParameter("status",status).setParameter("level",level).setParameter("lowerBound",from).setParameter("upperBound",to)
					.setParameter("afterTime",after).setParameter("afterId",afterId).setMaxResults(3).getResultList().isEmpty());
		}
		return sql.get();
	}
	String numbered(String query) {
		var matcher=java.util.regex.Pattern.compile("\\?").matcher(query);var result=new StringBuilder();int index=0;
		while(matcher.find())matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement("$"+(++index)));
		return matcher.appendTail(result).toString();
	}
	@Test void previousSqlPlacesLowerBoundNullCheckAtSixNotStatusOrCursor() throws Exception {
		String old=hql.replace("cast(:lowerBound as Instant)",":lowerBound").replace("cast(:upperBound as Instant)",":upperBound")
				.replace("cast(:afterTime as Instant)",":afterTime");
		String generated=numbered(execute(old,Set.of(environment),null,null,null,null,null,null));
		assertTrue(generated.matches(".*\\(\\$6 is null or \\w+\\.last_seen_at>=\\$7\\).*"),generated);
		verify(statement).setNull(6,Types.TIMESTAMP);
	}
	@Test void postgresDriverLeavesNullTimestampTypeUnspecified() throws Exception {
		var driver=mock(Class.forName("org.postgresql.jdbc.PgPreparedStatement").asSubclass(PreparedStatement.class),CALLS_REAL_METHODS);
		var parameters=mock(org.postgresql.core.ParameterList.class);when(parameters.getParameterCount()).thenReturn(14);
		doReturn(false).when(driver).isClosed();
		org.springframework.test.util.ReflectionTestUtils.setField(driver,"preparedParameters",parameters);
		driver.setNull(6,Types.TIMESTAMP);
		verify(parameters).setNull(6,org.postgresql.core.Oid.UNSPECIFIED);
	}
	@Test void omittedFiltersGiveEveryTemporalNullCheckAnExplicitTimezoneType() throws Exception {
		String generated=numbered(execute(hql,Set.of(environment),null,null,null,null,null,null));
		for(int position:new int[]{6,8,10}) {
			assertTrue(generated.contains("cast($"+position+" as timestamp(6) with time zone) is null"),generated);
			verify(statement).setNull(position,Types.TIMESTAMP);
		}
		assertTrue(generated.matches(".*environment_id in \\(\\$1\\).*"),generated);
		assertTrue(generated.contains("last_seen_at>=") && generated.contains("last_seen_at<"));
		assertTrue(generated.matches(".*order by \\w+\\.last_seen_at desc,\\w+\\.id desc fetch first \\$14 rows only"),generated);
	}
	@Test void combinedFiltersAndKeysetKeepValuesAndPredicates() throws Exception {
		UUID id=UUID.fromString("80000000-0000-0000-0000-000000000000");
		String generated=execute(hql,Set.of(environment),IncidentEntity.Status.RESOLVED,Level.CRITICAL,time.minusSeconds(10),time.plusSeconds(10),time,id);
		assertTrue(generated.matches(".*last_seen_at<\\? or \\(\\w+\\.last_seen_at=\\? and \\w+\\.id<\\?\\)\\).*"),generated);
		verify(statement).setString(2,"RESOLVED");verify(statement).setString(4,"CRITICAL");
		verify(statement).setTimestamp(eq(6),eq(java.sql.Timestamp.from(time.minusSeconds(10))),argThat(c -> "UTC".equals(c.getTimeZone().getID())));
		verify(statement).setTimestamp(eq(8),eq(java.sql.Timestamp.from(time.plusSeconds(10))),argThat(c -> "UTC".equals(c.getTimeZone().getID())));
		verify(statement).setTimestamp(eq(10),eq(java.sql.Timestamp.from(time)),argThat(c -> "UTC".equals(c.getTimeZone().getID())));
		verify(statement).setObject(13,id);verify(statement).setInt(14,3);
	}
	@Test void oneSidedWindowsAndMultipleAuthorizedEnvironmentsRemainTyped() {
		for(boolean lowerOnly:new boolean[]{true,false}) {
			String generated=execute(hql,Set.of(environment,UUID.fromString("00000000-0000-0000-0000-000000000001")),null,null,
					lowerOnly?time:null,lowerOnly?null:time,null,null);
			assertTrue(generated.matches(".*environment_id in \\(\\?,\\?\\).*"),generated);
			assertEquals(3,generated.split("timestamp\\(6\\) with time zone",-1).length-1);
		}
	}
	@Test void manualTargetProjectionIsScopedAndDoesNotHydrateAnIncidentBeforeLock() throws Exception {
		var query=IncidentRepository.class.getMethod("resolutionScope",UUID.class,Set.class).getAnnotation(Query.class).value();
		UUID id=UUID.randomUUID();
		try(var session=factory.openSession()) {
			assertTrue(session.createSelectionQuery(query,jakarta.persistence.Tuple.class).setParameter("id",id)
					.setParameter("environments",Set.of(environment)).getResultList().isEmpty());
		}
		String generated=sql.get();assertTrue(generated.contains("environment_id") && generated.contains("fingerprint"));
		assertFalse(generated.contains("error_count"));assertFalse(generated.contains("resolution_source"));
		assertTrue(generated.matches(".*where .*id=\\? and .*environment_id in \\(\\?\\).*"),generated);
		verify(statement).setObject(1,id);verify(statement).setObject(2,environment);
	}
	@Test void cutoffQueryUsesTypedSourceAndScopeWithoutNullableGuards() throws Exception {
		var source=com.h.vanioak.modules.alert.api.IncidentResolutionFacade.Source.MANUAL;
		var query=IncidentRepository.class.getMethod("latestManualResolution",UUID.class,String.class,source.getClass())
				.getAnnotation(Query.class).value();
		try(var session=factory.openSession()) {
			assertTrue(session.createSelectionQuery(query,Instant.class).setParameter("env",environment)
					.setParameter("fingerprint","a".repeat(64)).setParameter("source",source).getResultList().isEmpty());
		}
		String generated=sql.get();assertTrue(generated.contains("max(") && generated.contains("resolved_at"),generated);
		assertTrue(generated.matches(".*environment_id=\\? and .*fingerprint=\\? and .*resolution_source=\\?.*"),generated);
		assertFalse(generated.contains("is null"));verify(statement).setObject(1,environment);verify(statement).setString(3,"MANUAL");
	}
}
