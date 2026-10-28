package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.IdentityException;

class IncidentApiServiceTest {
	final IncidentReadFacade reads=mock(IncidentReadFacade.class);
	final ApplicationFacade apps=mock(ApplicationFacade.class);
	final ApplicationAccessFacade access=mock(ApplicationAccessFacade.class);
	final IncidentApiService api=new IncidentApiService(reads,apps,access);
	final UUID actor=UUID.randomUUID(),app=UUID.randomUUID(),env=UUID.randomUUID(),otherApp=UUID.randomUUID(),otherEnv=UUID.randomUUID(),id=UUID.randomUUID();
	final Authentication engineer=auth("ENGINEER"),admin=auth("ADMIN");
	final IncidentReadFacade.Filters filters=new IncidentReadFacade.Filters(null,null,null,null,null,50);
	Authentication auth(String role) { return UsernamePasswordAuthenticationToken.authenticated(actor.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
	ApplicationFacade.ApplicationView application(UUID app,UUID env) {
		return new ApplicationFacade.ApplicationView(app,"name-"+app,null,ApplicationFacade.Status.DISABLED,
				List.of(new ApplicationFacade.EnvironmentView(env,ApplicationFacade.EnvironmentName.TEST,ApplicationFacade.Status.DISABLED)));
	}
	IncidentReadFacade.Item item(UUID env) { return new IncidentReadFacade.Item(id,env,Level.ERROR,IncidentReadFacade.Status.OPEN,"a".repeat(64),"message",1,Instant.EPOCH,Instant.EPOCH,null); }
	@BeforeEach void setup() {
		when(apps.list(actor,null,null,100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(application(app,env)),null));
		when(reads.list(any(),any())).thenReturn(new IncidentReadFacade.Page(List.of(item(env)),null));
	}

	@Test void engineerScopeComesFromMembershipIncludingDisabledHistoricalData() {
		var result=api.list(engineer,null,null,filters);assertEquals(app,result.items().getFirst().application().id());
		assertEquals("TEST",result.items().getFirst().environment().name());assertNull(result.items().getFirst().events());
		var capture=ArgumentCaptor.forClass(IncidentReadFacade.Scope.class);verify(reads).list(capture.capture(),eq(filters));
		assertEquals(Set.of(env),capture.getValue().environmentIds());assertEquals(actor,capture.getValue().actorId());
	}

	@Test void adminScopeIncludesAllPagedApplications() {
		when(apps.list(actor,null,null,100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(application(app,env)),"next"));
		when(apps.list(actor,null,"next",100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(application(otherApp,otherEnv)),null));
		api.list(admin,null,null,filters);
		var capture=ArgumentCaptor.forClass(IncidentReadFacade.Scope.class);verify(reads).list(capture.capture(),eq(filters));
		assertEquals(Set.of(env,otherEnv),capture.getValue().environmentIds());
	}

	@Test void explicitForeignApplicationAndForgedParentAreDeniedBeforeReads() {
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED)).when(access).requireApplicationReadAccess(actor,otherApp);
		doThrow(new IdentityException(com.h.vanioak.modules.identity.api.ErrorCode.ACCESS_DENIED)).when(access).requireEnvironmentReadAccess(actor,app,otherEnv);
		assertThrows(IdentityException.class,() -> api.list(engineer,otherApp,null,filters));
		assertThrows(IdentityException.class,() -> api.list(engineer,app,otherEnv,filters));verifyNoInteractions(reads,apps);
	}

	@Test void environmentOnlyIsResolvedFromVisibleMetadataAndGuarded() {
		api.list(engineer,null,env,filters);verify(access).requireEnvironmentReadAccess(actor,app,env);
		clearInvocations(reads);assertEquals(ErrorCode.ACCESS_DENIED,assertThrows(AlertException.class,() -> api.list(engineer,null,otherEnv,filters)).getErrorCode());
		verifyNoInteractions(reads);
	}

	@Test void explicitApplicationNarrowsEvenAdminScope() {
		when(apps.list(actor,null,null,100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(application(app,env),application(otherApp,otherEnv)),null));
		api.list(admin,app,null,filters);verify(access).requireApplicationReadAccess(actor,app);
		var capture=ArgumentCaptor.forClass(IncidentReadFacade.Scope.class);verify(reads).list(capture.capture(),eq(filters));assertEquals(Set.of(env),capture.getValue().environmentIds());
	}

	@Test void foreignOrUnknownDetailDoesNotRevealExistenceToEngineer() {
		when(reads.detail(any(),eq(id))).thenReturn(Optional.empty());
		assertEquals(ErrorCode.ACCESS_DENIED,assertThrows(AlertException.class,() -> api.detail(engineer,id)).getErrorCode());
		assertEquals(ErrorCode.INCIDENT_NOT_FOUND,assertThrows(AlertException.class,() -> api.detail(admin,id)).getErrorCode());
	}

	@Test void detailMapsOnlyAuthorizedMetadataAndLifecycleFields() {
		var event=new IncidentReadFacade.Event(UUID.randomUUID(),"OPENED",Instant.EPOCH,1L,"message",null);
		when(reads.detail(any(),eq(id))).thenReturn(Optional.of(new IncidentReadFacade.Detail(item(env),List.of(event))));
		var result=api.detail(engineer,id);assertEquals(app,result.application().id());assertEquals(event.id(),result.events().getFirst().id());
		assertEquals("OPENED",result.events().getFirst().eventType());
	}

	@Test void sameAuthenticationAfterRevocationReceivesFreshEmptyScope() {
		api.list(engineer,null,null,filters);clearInvocations(reads);
		when(apps.list(actor,null,null,100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(),null));
		when(reads.list(any(),any())).thenReturn(new IncidentReadFacade.Page(List.of(),null));
		assertTrue(api.list(engineer,null,null,filters).items().isEmpty());
		var capture=ArgumentCaptor.forClass(IncidentReadFacade.Scope.class);verify(reads).list(capture.capture(),eq(filters));assertTrue(capture.getValue().environmentIds().isEmpty());
		clearInvocations(reads);assertThrows(AlertException.class,() -> api.list(engineer,app,null,filters));verifyNoInteractions(reads);
	}

	@Test void missingOrUnknownRoleFailsBeforeAnyMetadata() {
		assertThrows(AlertException.class,() -> api.list(null,null,null,filters));
		assertThrows(AlertException.class,() -> api.detail(auth("OTHER"),id));verifyNoInteractions(reads,apps,access);
	}

	@Test void metadataFailureOrRepeatedPageFailsClosedWithoutPartialResults() {
		when(apps.list(actor,null,null,100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(application(app,env)),"loop"));
		when(apps.list(actor,null,"loop",100)).thenReturn(new ApplicationFacade.ApplicationPage(List.of(),"loop"));
		assertEquals(ErrorCode.UNAVAILABLE,assertThrows(AlertException.class,() -> api.list(engineer,null,null,filters)).getErrorCode());verifyNoInteractions(reads);
	}
}
