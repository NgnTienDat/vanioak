package com.h.vanioak.modules.alert.internal.incident;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade;
import com.h.vanioak.modules.alert.internal.engine.IncidentEntity;
import com.h.vanioak.modules.alert.internal.engine.IncidentRepository;
import com.h.vanioak.modules.alert.internal.engine.IncidentHistoryRepository;
import lombok.RequiredArgsConstructor;

@Service @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) @RequiredArgsConstructor
public class IncidentReadService implements IncidentReadFacade {
	private final IncidentRepository incidents;
	private final IncidentHistoryRepository history;
	private final IncidentCursor cursor;

	@Override
	public Page list(Scope scope, Filters filters) {
		if (filters.limit()<1 || filters.limit()>100 || (filters.from()!=null && filters.to()!=null && !filters.from().isBefore(filters.to())))
			throw new AlertException(ErrorCode.INVALID_REQUEST);
		Instant from=ceilMicros(filters.from()),to=ceilMicros(filters.to());
		String context=cursor.context(scope,filters);
		var key=filters.cursor()==null ? null : cursor.decode(filters.cursor(),context);
		if (key!=null && ((from!=null && key.time().isBefore(from)) || (to!=null && !key.time().isBefore(to))))
			throw new AlertException(ErrorCode.INVALID_CURSOR);
		if (scope.environmentIds().isEmpty() || (from!=null && from.equals(to))) return new Page(java.util.List.of(),null);
		var rows=incidents.readPage(scope.environmentIds(),filters.status()==null ? null : IncidentEntity.Status.valueOf(filters.status().name()),
				filters.level(),from,to,key==null ? null : key.time(),key==null ? null : key.id(),PageRequest.of(0,filters.limit()+1));
		var items=rows.stream().limit(filters.limit()).map(this::view).toList();
		String next=rows.size()>filters.limit() ? cursor.encode(new IncidentCursor.Key(items.getLast().lastSeenAt(),items.getLast().id()),context) : null;
		return new Page(items,next);
	}

	@Override
	public Optional<Detail> detail(Scope scope, UUID id) {
		if (scope.environmentIds().isEmpty()) return Optional.empty();
		return incidents.readDetail(id,scope.environmentIds()).map(incident -> {
			var events=history.recent(id,PageRequest.of(0,100)).stream().map(h -> new Event(h.getId(),h.getEventType().name(),
					h.getEventAt(),h.getErrorCount(),h.getMessage(),h.getMetadata())).toList();
			return new Detail(view(incident),events);
		});
	}

	private Item view(IncidentEntity i) {
		return new Item(i.getId(),i.getEnvironmentId(),i.getLevel(),Status.valueOf(i.getStatus().name()),i.getFingerprint(),
				i.getMessage(),i.getErrorCount(),i.getFirstSeenAt(),i.getLastSeenAt(),i.getResolvedAt());
	}

	private Instant ceilMicros(Instant value) {
		if (value==null) return null;
		try {
			Math.multiplyExact(value.getEpochSecond(),1_000_000L);
			return value.getNano()%1000==0 ? value : value.truncatedTo(ChronoUnit.MICROS).plusNanos(1000);
		} catch (ArithmeticException | java.time.DateTimeException ex) { throw new AlertException(ErrorCode.INVALID_REQUEST); }
	}
}
