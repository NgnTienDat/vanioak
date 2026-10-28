package com.h.vanioak.modules.alert.internal.incident;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Filters;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Scope;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;
import com.h.vanioak.modules.alert.internal.engine.IncidentEntity;
import com.h.vanioak.modules.alert.internal.engine.IncidentHistory;
import com.h.vanioak.modules.alert.internal.engine.IncidentHistoryRepository;
import com.h.vanioak.modules.alert.internal.engine.IncidentRepository;
import tools.jackson.databind.ObjectMapper;

class IncidentReadServiceTest {
	final IncidentRepository rows=mock(IncidentRepository.class);
	final IncidentHistoryRepository history=mock(IncidentHistoryRepository.class);
	final IncidentReadService service=new IncidentReadService(rows,history,new IncidentCursor(new ObjectMapper()));
	final UUID actor=UUID.randomUUID(),env=UUID.randomUUID(),id=UUID.randomUUID();
	final Instant time=Instant.parse("2026-10-27T00:00:00.123456Z");
	final Scope scope=new Scope(actor,Set.of(env),null,null);
	Filters filters(String cursor,int limit) { return new Filters(null,null,null,null,cursor,limit); }
	IncidentEntity row(UUID id) {
		var row=BeanUtils.instantiateClass(IncidentEntity.class);
		for(var e:Map.<String,Object>of("id",id,"environmentId",env,"level",Level.ERROR,"status",IncidentEntity.Status.OPEN,
				"fingerprint","a".repeat(64),"message","message","errorCount",5L,"firstSeenAt",time.minusSeconds(1),"lastSeenAt",time).entrySet())
			ReflectionTestUtils.setField(row,e.getKey(),e.getValue());
		return row;
	}

	@Test void forwardsAuthorizedEnvironmentsAndEveryFilterBeforeLimit() {
		Instant from=time.minusSeconds(5),to=time.plusSeconds(1);
		when(rows.readPage(scope.environmentIds(),IncidentEntity.Status.RESOLVED,Level.CRITICAL,from,to,null,null,PageRequest.of(0,3)))
				.thenReturn(List.of());
		var page=service.list(scope,new Filters(Status.RESOLVED,Level.CRITICAL,from,to,null,2));
		assertTrue(page.items().isEmpty());assertNull(page.nextCursor());
		verify(rows).readPage(scope.environmentIds(),IncidentEntity.Status.RESOLVED,Level.CRITICAL,from,to,null,null,PageRequest.of(0,3));
		verifyNoInteractions(history);
	}

	@Test void sameTimestampKeysetUsesLastReturnedUuidNotLookahead() {
		UUID high=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),mid=UUID.fromString("80000000-0000-0000-0000-000000000000"),low=UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(rows.readPage(scope.environmentIds(),null,null,null,null,null,null,PageRequest.of(0,2))).thenReturn(List.of(row(high),row(mid)));
		var first=service.list(scope,filters(null,1));assertEquals(high,first.items().getFirst().id());
		when(rows.readPage(scope.environmentIds(),null,null,null,null,time,high,PageRequest.of(0,2))).thenReturn(List.of(row(mid),row(low)));
		var second=service.list(scope,filters(first.nextCursor(),1));assertEquals(mid,second.items().getFirst().id());
		when(rows.readPage(scope.environmentIds(),null,null,null,null,time,mid,PageRequest.of(0,2))).thenReturn(List.of(row(low)));
		var last=service.list(scope,filters(second.nextCursor(),1));assertEquals(low,last.items().getFirst().id());assertNull(last.nextCursor());
		verifyNoInteractions(history);
	}

	@Test void emptyScopeNeverQueriesAndInvalidBoundsFailBeforeSql() {
		var empty=new Scope(actor,Set.of(),null,null);
		assertTrue(service.list(empty,filters(null,50)).items().isEmpty());assertTrue(service.detail(empty,id).isEmpty());
		for(int limit:new int[]{0,101})assertThrows(AlertException.class,() -> service.list(scope,filters(null,limit)));
		assertThrows(AlertException.class,() -> service.list(scope,new Filters(null,null,time,time,null,50)));
		assertThrows(AlertException.class,() -> service.list(scope,new Filters(null,null,Instant.MAX,null,null,50)));
		verifyNoInteractions(rows,history);
	}

	@Test void subMicrosecondBoundsAreCeiledForBothHalfOpenEdges() {
		Instant from=time.plusNanos(1),to=time.plusNanos(2001);
		when(rows.readPage(scope.environmentIds(),null,null,time.plusNanos(1000),time.plusNanos(3000),null,null,PageRequest.of(0,51)))
				.thenReturn(List.of());
		service.list(scope,new Filters(null,null,from,to,null,50));
		verify(rows).readPage(scope.environmentIds(),null,null,time.plusNanos(1000),time.plusNanos(3000),null,null,PageRequest.of(0,51));
	}

	@Test void rangeWithNoRepresentableTimestampIsSuccessfulEmpty() {
		assertTrue(service.list(scope,new Filters(null,null,time.plusNanos(1),time.plusNanos(999),null,50)).items().isEmpty());
		verifyNoInteractions(rows,history);
	}

	@Test void scopedMissingDetailNeverFetchesHistory() {
		when(rows.readDetail(id,scope.environmentIds())).thenReturn(Optional.empty());
		assertTrue(service.detail(scope,id).isEmpty());verifyNoInteractions(history);verify(rows).readDetail(id,scope.environmentIds());
	}

	@Test void detailRequestsOnlyNewest100AndPreservesHistoryMetadata() {
		when(rows.readDetail(id,scope.environmentIds())).thenReturn(Optional.of(row(id)));
		var h=BeanUtils.instantiateClass(IncidentHistory.class);
		ReflectionTestUtils.setField(h,"id",UUID.randomUUID());ReflectionTestUtils.setField(h,"eventType",IncidentHistory.Type.COUNT_UPDATED);
		ReflectionTestUtils.setField(h,"eventAt",time);ReflectionTestUtils.setField(h,"errorCount",5L);
		ReflectionTestUtils.setField(h,"metadata",Map.of("source","engine"));
		when(history.recent(id,PageRequest.of(0,100))).thenReturn(List.of(h));
		var detail=service.detail(scope,id).orElseThrow();assertEquals(5,detail.incident().errorCount());
		assertEquals("COUNT_UPDATED",detail.events().getFirst().eventType());assertEquals(Map.of("source","engine"),detail.events().getFirst().metadata());
		verify(history).recent(id,PageRequest.of(0,100));
	}

	@Test void queriesDeclareScopeAndHistoryOrdering() throws Exception {
		var query=IncidentRepository.class.getMethod("readPage",Set.class,IncidentEntity.Status.class,Level.class,Instant.class,Instant.class,Instant.class,UUID.class,org.springframework.data.domain.Pageable.class)
				.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
		assertTrue(query.contains("i.environmentId in :environments"));assertTrue(query.contains("i.lastSeenAt>=:lowerBound"));assertTrue(query.contains("i.lastSeenAt<:upperBound"));
		assertTrue(query.indexOf("where")<query.indexOf("order by i.lastSeenAt desc, i.id desc"));
		String detail=IncidentRepository.class.getMethod("readDetail",UUID.class,Set.class).getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
		assertTrue(detail.contains("i.id=:id and i.environmentId in :environments"));
		String h=IncidentHistoryRepository.class.getMethod("recent",UUID.class,org.springframework.data.domain.Pageable.class).getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
		assertTrue(h.contains("where h.incidentId=:id order by h.eventAt desc, h.id desc"));
		assertTrue(IncidentReadService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
		assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,IncidentReadService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation());
	}
}
