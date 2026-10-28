package com.h.vanioak.modules.alert.internal.rule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Create;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Update;
import com.h.vanioak.modules.alert.api.ErrorCode;

class AlertRuleServiceTest {
	final AlertRuleRepository rules = mock(AlertRuleRepository.class);
	final AlertRuleService service = new AlertRuleService(rules);
	final UUID actor = UUID.randomUUID(), env = UUID.randomUUID(), id = UUID.randomUUID();
	Create command(Level level) { return new Create(env, level, 10, 60, 300, 300, true); }
	AlertRuleEntity entity(UUID identity) {
		var entity = new AlertRuleEntity(actor, command(Level.ERROR));
		ReflectionTestUtils.setField(entity, "id", identity); entity.onCreate(); return entity;
	}

	@ParameterizedTest @EnumSource(Level.class)
	void createsBothLevelsWithExistingSchemaFields(Level level) {
		when(rules.saveAndFlush(any())).thenAnswer(call -> {
			AlertRuleEntity entity = call.getArgument(0); assertEquals(actor, entity.getCreatedBy());
			ReflectionTestUtils.setField(entity, "id", id); entity.onCreate(); return entity;
		});
		var result = service.create(actor, command(level));
		assertEquals(id, result.id()); assertEquals(env, result.environmentId()); assertEquals(level, result.level());
		assertEquals(10, result.threshold()); assertTrue(result.enabled()); assertNotNull(result.createdAt());
		assertEquals(result.createdAt(), result.updatedAt());
	}

	@Test void invalidCommandsNeverReachPersistence() {
		for (Integer bad : new Integer[]{null, 0, -1}) {
			assertThrows(AlertException.class, () -> service.create(actor, new Create(env, Level.ERROR, bad, 60, 1, 1, true)));
			assertThrows(AlertException.class, () -> service.create(actor, new Create(env, Level.ERROR, 1, bad, 1, 1, true)));
			assertThrows(AlertException.class, () -> service.create(actor, new Create(env, Level.ERROR, 1, 1, bad, 1, true)));
			assertThrows(AlertException.class, () -> service.create(actor, new Create(env, Level.ERROR, 1, 1, 1, bad, true)));
		}
		assertThrows(AlertException.class, () -> service.create(null, command(Level.ERROR)));
		assertThrows(AlertException.class, () -> service.create(actor, new Create(null, Level.ERROR, 1, 1, 1, 1, true)));
		assertThrows(AlertException.class, () -> service.create(actor, new Create(env, null, 1, 1, 1, 1, true)));
		assertThrows(AlertException.class, () -> service.update(id, new Update(0, null, null, null, null)));
		verifyNoInteractions(rules);
	}

	@Test void databaseUniqueConstraintMapsRacingDuplicateToConflictOnly() {
		var violation = mock(ConstraintViolationException.class);
		when(violation.getSQLState()).thenReturn("23505"); when(violation.getConstraintName()).thenReturn("uq_alert_rules_scope_level");
		var failure = new DataIntegrityViolationException("private database detail", violation);
		when(rules.saveAndFlush(any())).thenReturn(entity(id)).thenThrow(failure);
		service.create(actor, command(Level.ERROR));
		assertEquals(ErrorCode.RULE_ALREADY_EXISTS, assertThrows(AlertException.class, () -> service.create(actor, command(Level.ERROR))).getErrorCode());
		when(violation.getConstraintName()).thenReturn("some_other_constraint");
		assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.create(actor, command(Level.ERROR))));
	}

	@Test void partialUpdatePreservesIdentityScopeCreatorAndCreationTime() {
		var entity = entity(id); var created = entity.getCreatedAt();
		when(rules.findLocked(id)).thenReturn(Optional.of(entity));
		var updated = service.update(id, new Update(20, null, 1, null, false));
		assertEquals(20, updated.threshold()); assertEquals(60, updated.windowSeconds());
		assertEquals(1, updated.cooldownSeconds()); assertEquals(300, updated.resolveAfterSeconds()); assertFalse(updated.enabled());
		assertEquals(id, updated.id()); assertEquals(env, updated.environmentId()); assertEquals(Level.ERROR, updated.level());
		assertEquals(actor, entity.getCreatedBy()); assertEquals(created, entity.getCreatedAt());
		verify(rules).flush();
	}

	@Test void emptyPatchAndRepeatedDisableAreNonDestructive() {
		var entity = entity(id); when(rules.findLocked(id)).thenReturn(Optional.of(entity));
		assertTrue(service.update(id, new Update(null, null, null, null, null)).enabled());
		assertFalse(service.disable(id).enabled()); assertFalse(service.disable(id).enabled());
		verify(rules, never()).delete(any()); assertEquals(10, entity.getThreshold());
	}

	@Test void missingRuleIsNotFound() {
		assertEquals(ErrorCode.RULE_NOT_FOUND, assertThrows(AlertException.class, () -> service.get(id)).getErrorCode());
		assertEquals(ErrorCode.RULE_NOT_FOUND, assertThrows(AlertException.class, () -> service.disable(id)).getErrorCode());
	}

	@Test void paginationUsesSqlBoundaryAndLastReturnedItemNotLookahead() {
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(rules.findRules(null, PageRequest.of(0, 2))).thenReturn(List.of(entity(first), entity(second)));
		var page = service.list(null, 1); assertEquals(List.of(first), page.items().stream().map(r -> r.id()).toList());
		assertEquals(first.toString(), new String(Base64.getUrlDecoder().decode(page.nextCursor()), StandardCharsets.UTF_8));
		when(rules.findRules(first, PageRequest.of(0, 2))).thenReturn(List.of(entity(second)));
		var last = service.list(page.nextCursor(), 1); assertEquals(second, last.items().getFirst().id()); assertNull(last.nextCursor());
		verify(rules).findRules(first, PageRequest.of(0, 2));
	}

	@Test void emptyListAndInvalidPagingFailClosed() {
		for (String cursor : List.of("bad!", "x".repeat(65), Base64.getUrlEncoder().encodeToString("1-1-1-1-1".getBytes(StandardCharsets.UTF_8))))
			assertEquals(ErrorCode.INVALID_CURSOR, assertThrows(AlertException.class, () -> service.list(cursor, 50)).getErrorCode());
		for (int limit : new int[]{0, -1, 101}) assertThrows(AlertException.class, () -> service.list(null, limit));
		verifyNoInteractions(rules);
		when(rules.findRules(null, PageRequest.of(0, 51))).thenReturn(List.of());
		var page = service.list(null, 50); assertTrue(page.items().isEmpty()); assertNull(page.nextCursor());
	}

	@Test void repositoryDeclaresDatabaseLockAndOrderedKeysetQuery() throws Exception {
		assertEquals(LockModeType.PESSIMISTIC_WRITE, AlertRuleRepository.class.getMethod("findLocked", UUID.class).getAnnotation(Lock.class).value());
		String query = AlertRuleRepository.class.getMethod("findRules", UUID.class, org.springframework.data.domain.Pageable.class).getAnnotation(Query.class).value();
		assertTrue(query.contains("r.id > :after")); assertTrue(query.indexOf("where") < query.indexOf("order by r.id"));
	}
}
