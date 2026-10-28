package com.h.vanioak.modules.alert.internal.incident;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Filters;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Scope;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;
import tools.jackson.databind.ObjectMapper;

class IncidentCursorTest {
	final ObjectMapper mapper=new ObjectMapper(); final IncidentCursor codec=new IncidentCursor(mapper);
	final UUID actor=UUID.randomUUID(),env=UUID.randomUUID();
	final Scope scope=new Scope(actor,Set.of(env),null,null);
	final Filters filters=new Filters(null,null,null,null,null,50);
	final IncidentCursor.Key key=new IncidentCursor.Key(Instant.parse("2026-10-27T00:00:00.123456Z"),UUID.randomUUID());
	@Test void canonicalRoundTripIsBoundedAndVersioned() {
		String context=codec.context(scope,filters),token=codec.encode(key,context);
		assertEquals(key,codec.decode(token,context));assertTrue(token.length()<2048);
		assertThrows(AlertException.class,() -> codec.decode(codec.encode(new IncidentCursor.Key(Instant.MAX.minusNanos(999),key.id()),context),context));
	}
	@Test void malformedWrongVersionMissingAndExtraFieldsFailClosed() {
		String context=codec.context(scope,filters),token=codec.encode(key,context);
		for(String value:new String[]{"","!","x".repeat(2049),Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))})
			assertThrows(AlertException.class,() -> codec.decode(value,context));
		String json=new String(Base64.getUrlDecoder().decode(token),java.nio.charset.StandardCharsets.UTF_8);
		for(String changed:new String[]{json.replace("\"v\":1","\"v\":2"),json+"{}",json.replace(".123456Z",".123456001Z"),json.replace("\"v\":1","\"extra\":1,\"v\":1")})
			assertThrows(AlertException.class,() -> codec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),context));
	}
	@Test void actorScopeAndFilterChangesInvalidateCursor() {
		String token=codec.encode(key,codec.context(scope,filters));
		assertThrows(AlertException.class,() -> codec.decode(token,codec.context(new Scope(UUID.randomUUID(),Set.of(env),null,null),filters)));
		assertThrows(AlertException.class,() -> codec.decode(token,codec.context(new Scope(actor,Set.of(),null,null),filters)));
		assertThrows(AlertException.class,() -> codec.decode(token,codec.context(scope,new Filters(Status.OPEN,null,null,null,null,50))));
	}
	@Test void environmentSetIsImmutableAndOrderIndependent() {
		UUID another=UUID.randomUUID();var mutable=new java.util.HashSet<>(Set.of(env,another));var s=new Scope(actor,mutable,null,null);mutable.clear();
		assertEquals(2,s.environmentIds().size());assertEquals(codec.context(s,filters),codec.context(new Scope(actor,Set.of(another,env),null,null),filters));
	}
}
