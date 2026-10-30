package com.h.vanioak.modules.identity.internal.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

import com.h.vanioak.modules.identity.internal.user.UserEntity;

/** Exercises the actual HQL/PostgreSQL translation with mocked JDBC, never a database. */
class AssignedEngineerQueryTest {
	private static SessionFactory factory;
	private static Connection connection;
	private static PreparedStatement statement;
	private static final AtomicReference<String> sql = new AtomicReference<>();
	private static String hql;

	@BeforeAll static void setup() throws Exception {
		hql = UserApplicationAccessRepository.class.getMethod("findAssignedEngineers", UUID.class, UUID.class, Pageable.class)
				.getAnnotation(Query.class).value();
		connection = mock(Connection.class);
		statement = mock(PreparedStatement.class);
		var result = mock(ResultSet.class);
		when(statement.executeQuery()).thenReturn(result);
		when(result.getStatement()).thenReturn(statement);
		when(connection.prepareStatement(anyString())).thenAnswer(call -> { sql.set(call.getArgument(0)); return statement; });
		when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenAnswer(call -> { sql.set(call.getArgument(0)); return statement; });
		when(connection.getAutoCommit()).thenReturn(true);
		var provider = mock(ConnectionProvider.class);
		when(provider.getConnection()).thenReturn(connection);
		var configuration = new Configuration().addAnnotatedClass(UserEntity.class).addAnnotatedClass(UserApplicationAccessEntity.class);
		configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
		configuration.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
		configuration.setProperty("hibernate.hbm2ddl.auto", "none");
		configuration.getProperties().put("hibernate.connection.provider_class", provider);
		factory = configuration.buildSessionFactory();
	}

	@AfterAll static void close() { if (factory != null) factory.close(); }
	@BeforeEach void reset() { clearInvocations(connection, statement); sql.set(null); }

	private String read(UUID applicationId, UUID after) {
		try (var session = factory.openSession()) {
			assertTrue(session.createSelectionQuery(hql, Object[].class).setParameter("applicationId", applicationId)
					.setParameter("after", after).setMaxResults(3).getResultList().isEmpty());
		}
		return sql.get();
	}

	@Test void realQueryBindsEachApplicationAndProjectsNoSecretOrStatusRestriction() throws Exception {
		for (UUID applicationId : new UUID[] { UUID.randomUUID(), UUID.randomUUID() }) {
			clearInvocations(statement);
			String generated = read(applicationId, null);
			assertTrue(generated.contains("application_id=?"), generated);
			assertTrue(generated.contains("user_id=") && generated.contains("exists("), generated);
			String userAlias = generated.split(" from users ", 2)[1].split(" ", 2)[0];
			assertTrue(generated.contains("user_id=" + userAlias + ".id"), generated);
			assertTrue(generated.contains("role='ENGINEER'"), generated);
			assertFalse(generated.contains("status="), generated);
			assertFalse(generated.contains("password") || generated.contains("created_at"), generated);
			verify(statement).setObject(1, applicationId);
		}
	}

	@Test void realQueryOrdersByUserIdAndUsesAnExclusiveCursorWithBoundedResults() throws Exception {
		UUID applicationId = UUID.randomUUID(), after = UUID.randomUUID();
		String generated = read(applicationId, after);
		assertTrue(generated.matches(".*\\.id>\\?.*order by .*\\.id.*fetch first \\? rows only.*"), generated);
		verify(statement).setObject(1, applicationId);
		verify(statement).setObject(2, after);
		verify(statement).setObject(3, after);
		verify(statement).setInt(4, 3);
	}
}
