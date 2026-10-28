package com.h.vanioak.modules.alert.internal.engine;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.h.vanioak.modules.identity.api.EnvironmentScopeFacade;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class IncidentRealtimePublisher implements AutoCloseable {
	private final EnvironmentScopeFacade scopes;
	private final RealtimeFacade realtime;
	private final ExecutorService worker;
	private final AtomicBoolean stopped=new AtomicBoolean();

	@Autowired
	public IncidentRealtimePublisher(EnvironmentScopeFacade scopes,RealtimeFacade realtime) {
		this(scopes,realtime,new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),task -> {
			var thread=new Thread(task,"alert-incident-realtime");thread.setDaemon(true);return thread;
		}));
	}
	IncidentRealtimePublisher(EnvironmentScopeFacade scopes,RealtimeFacade realtime,ExecutorService worker) {
		this.scopes=scopes;this.realtime=realtime;this.worker=worker;
	}

	record Snapshot(UUID eventId,String type,Instant occurredAt,UUID incidentId,UUID environmentId,
			String level,String status,String message,Long errorCount,Instant lastSeenAt,Instant resolvedAt) { }

	void register(IncidentEntity incident,IncidentHistory history) {
		try {
			if(!TransactionSynchronizationManager.isActualTransactionActive()
					|| !TransactionSynchronizationManager.isSynchronizationActive()) { skipped("no-transaction");return; }
			var snapshot=new Snapshot(history.getId(),switch(history.getEventType()) {
				case OPENED -> "incident.created";case COUNT_UPDATED -> "incident.updated";case RESOLVED -> "incident.resolved";
			},history.getEventAt(),incident.getId(),incident.getEnvironmentId(),incident.getLevel().name(),incident.getStatus().name(),
					history.getEventType()==IncidentHistory.Type.OPENED?incident.getMessage():null,
					history.getEventType()==IncidentHistory.Type.RESOLVED?null:incident.getErrorCount(),
					history.getEventType()==IncidentHistory.Type.COUNT_UPDATED?incident.getLastSeenAt():null,
					history.getEventType()==IncidentHistory.Type.RESOLVED?incident.getResolvedAt():null);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() { enqueue(snapshot); }
			});
		} catch(RuntimeException ex) { skipped("snapshot-unavailable"); }
	}
	private void enqueue(Snapshot snapshot) {
		if(stopped.get()) { skipped("shutdown");return; }
		try { worker.execute(() -> deliver(snapshot)); }
		catch(RuntimeException ex) { skipped("admission-unavailable"); }
	}
	private void deliver(Snapshot snapshot) {
		if(stopped.get()) return;
		try {
			var app=scopes.findApplicationId(snapshot.environmentId());
			if(app.isEmpty()) { skipped("scope-unavailable");return; }
			if(stopped.get()) return;
			var result=realtime.publishIncident(new RealtimeFacade.IncidentEvent(snapshot.eventId(),snapshot.type(),snapshot.occurredAt(),
					"alert",1,snapshot.incidentId(),app.get(),snapshot.environmentId(),snapshot.level(),snapshot.status(),snapshot.message(),
					snapshot.errorCount(),snapshot.lastSeenAt(),snapshot.resolvedAt()));
			if(result==null || !result.accounted()) skipped("realtime-unavailable");
		} catch(RuntimeException ex) { skipped("delivery-unavailable"); }
	}
	private void skipped(String reason) { log.debug("Incident realtime attempt skipped: {}",reason); }
	@Override @PreDestroy public void close() {
		if(!stopped.compareAndSet(false,true)) return;
		worker.shutdownNow();
		try { if(!worker.awaitTermination(5,TimeUnit.SECONDS)) log.warn("Incident realtime worker did not settle at shutdown"); }
		catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
	}
}
