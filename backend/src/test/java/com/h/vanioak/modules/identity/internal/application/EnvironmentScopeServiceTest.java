package com.h.vanioak.modules.identity.internal.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;

class EnvironmentScopeServiceTest {
	final EnvironmentRepository repository=mock(EnvironmentRepository.class);
	final EnvironmentScopeService service=new EnvironmentScopeService(repository);
	@Test void resolvesParentIncludingDisabledHistoricalEnvironmentWithoutStatusFilter() {
		UUID id=UUID.randomUUID(),app=UUID.randomUUID();var env=new EnvironmentEntity(app,EnvironmentName.DEV);
		ReflectionTestUtils.setField(env,"status",Status.DISABLED);when(repository.findById(id)).thenReturn(Optional.of(env));
		assertEquals(Optional.of(app),service.findApplicationId(id));verify(repository).findById(id);verifyNoMoreInteractions(repository);
	}
	@Test void unknownEnvironmentReturnsEmptyAndNullCannotQuery() {
		UUID id=UUID.randomUUID();when(repository.findById(id)).thenReturn(Optional.empty());assertTrue(service.findApplicationId(id).isEmpty());
		assertThrows(NullPointerException.class,() -> service.findApplicationId(null));verify(repository).findById(id);verifyNoMoreInteractions(repository);
	}
	@Test void dependencyFailureIsNotFabricatedAsAnotherScope() {
		UUID id=UUID.randomUUID();when(repository.findById(id)).thenThrow(new IllegalStateException("database unavailable"));
		assertThrows(IllegalStateException.class,() -> service.findApplicationId(id));
	}
	@Test void lookupDeclaresItsOwnReadOnlyTransaction() {
		var tx=EnvironmentScopeService.class.getAnnotation(Transactional.class);assertTrue(tx.readOnly());assertEquals(Propagation.REQUIRES_NEW,tx.propagation());
	}
}
