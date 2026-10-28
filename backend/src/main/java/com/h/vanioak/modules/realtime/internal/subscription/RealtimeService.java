package com.h.vanioak.modules.realtime.internal.subscription;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import jakarta.annotation.PreDestroy;

import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeJson;

import tools.jackson.databind.ObjectMapper;

public final class RealtimeService implements RealtimeFacade {
	private static final int MAX_CLIENTS = 32;
	private static final int MAX_PER_USER = 4;
	private static final int MAX_FRAMES = 64;
	private static final int MAX_BYTES = 1_048_576;
	private static final int DRAIN_QUANTUM = 16;
	private static final Duration HEARTBEAT = Duration.ofSeconds(15);
	private static final Duration LIFETIME = Duration.ofMinutes(5);
	private static final long TICK_MILLIS = 50;

	private final boolean enabled;
	private final BooleanSupplier consumerReady;
	private final Clock clock;
	private final ObjectMapper mapper;
	private final ThreadPoolExecutor writers = new ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS,
			new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "realtime-writer"); thread.setDaemon(true); return thread; });
	private final ThreadPoolExecutor closers = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(MAX_CLIENTS), task -> { Thread thread = new Thread(task, "realtime-close"); thread.setDaemon(true); return thread; });
	private final List<Client> owned = new ArrayList<>();
	private boolean stopping;

	public RealtimeService(boolean enabled, BooleanSupplier consumerReady, Clock clock, ObjectMapper mapper) {
		this.enabled = enabled;
		this.consumerReady = Objects.requireNonNull(consumerReady);
		this.clock = Objects.requireNonNull(clock);
		this.mapper = Objects.requireNonNull(mapper);
	}

	@Override
	public synchronized boolean ready() {
		return enabled && !stopping && consumerReady.getAsBoolean();
	}

	@Override
	public Subscription subscribe(UUID actorId, Filters filters, Authorization authorization, Sink sink) {
		Objects.requireNonNull(actorId);
		Objects.requireNonNull(filters);
		Objects.requireNonNull(authorization);
		Objects.requireNonNull(sink);
		Client client;
		synchronized (this) {
			if (!ready()) throw new RealtimeException(ErrorCode.NOT_READY);
			if (owned.size() >= MAX_CLIENTS || owned.stream().filter(c -> c.actorId.equals(actorId)).count() >= MAX_PER_USER)
				throw new RealtimeException(ErrorCode.OVERLOADED);
			client = new Client(actorId, filters, authorization, sink);
			owned.add(client);
			try {
				writers.execute(client::writeLoop);
			} catch (RejectedExecutionException ex) {
				owned.remove(client);
				throw new RealtimeException(ErrorCode.OVERLOADED);
			}
		}
		return client;
	}

	@Override
	public Fanout publishLog(LogEvent event) {
		Objects.requireNonNull(event);
		if (event.eventId() == null || event.applicationId() == null || event.environmentId() == null
				|| event.hostIp() == null || event.message() == null || event.timestamp() == null || event.traceId() == null
				|| event.metadata() == null || !validLevel(event.level())) throw new IllegalArgumentException("Invalid log event");
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("event_id", event.eventId());
		data.put("application_id", event.applicationId());
		data.put("environment_id", event.environmentId());
		data.put("host_ip", event.hostIp());
		data.put("level", event.level());
		data.put("message", event.message());
		data.put("timestamp", event.timestamp().toString());
		data.put("trace_id", event.traceId());
		data.put("metadata", event.metadata());
		return encodeAndFanout("log", event.applicationId(), event.environmentId(), event.level(), data);
	}

	@Override
	public Fanout publishIncident(IncidentEvent event) {
		Objects.requireNonNull(event);
		if (event.eventId() == null || event.occurredAt() == null || !"alert".equals(event.producer())
				|| event.schemaVersion() != 1 || event.incidentId() == null || event.applicationId() == null || event.environmentId() == null
				|| !validLevel(event.level())) throw new IllegalArgumentException("Invalid incident scope");
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("incident_id", event.incidentId());
		switch (event.type()) {
			case "incident.created" -> {
				if (!"OPEN".equals(event.status()) || event.message() == null || invalidCount(event.errorCount()))
					throw new IllegalArgumentException("Invalid incident creation");
				data.put("application_id", event.applicationId());
				data.put("environment_id", event.environmentId());
				data.put("level", event.level());
				data.put("status", event.status());
				data.put("message", event.message());
				data.put("error_count", event.errorCount());
			}
			case "incident.updated" -> {
				if (!"OPEN".equals(event.status()) || invalidCount(event.errorCount()) || event.lastSeenAt() == null)
					throw new IllegalArgumentException("Invalid incident update");
				data.put("error_count", event.errorCount());
				data.put("last_seen_at", event.lastSeenAt().toString());
			}
			case "incident.resolved" -> {
				if (!"RESOLVED".equals(event.status()) || event.resolvedAt() == null)
					throw new IllegalArgumentException("Invalid incident resolution");
				data.put("status", event.status());
				data.put("resolved_at", event.resolvedAt().toString());
			}
			default -> throw new IllegalArgumentException("Unsupported incident event");
		}
		return encodeAndFanout(event.type(), event.applicationId(), event.environmentId(), event.level(), data);
	}

	private static boolean invalidCount(Long count) { return count == null || count < 0; }
	private static boolean validLevel(String level) { return List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level); }
	private Fanout encodeAndFanout(String name, UUID app, UUID env, String level, Map<String, Object> data) {
		try {
			byte[] json = RealtimeJson.encode(mapper, data, RealtimeJson.FRAME_LIMIT - framing(name));
			return fanout(new Frame(name, app, env, level, new String(json, StandardCharsets.UTF_8)));
		} catch (RealtimeJson.CapacityException ex) { return new Fanout(false, 0, 0); }
	}
	private static int framing(String name) { return name.length() + 14; }

	public void unavailable() {
		List<Client> clients;
		synchronized (this) { clients = List.copyOf(owned); }
		clients.forEach(Client::close);
	}

	private Fanout fanout(Frame frame) {
		if (!ready() || frame.bytes > MAX_BYTES) return new Fanout(false, 0, 0);
		List<Client> clients;
		synchronized (this) { clients = List.copyOf(owned); }
		int queued = 0;
		int closed = 0;
		for (Client client : clients) {
			if (!client.matches(frame)) continue;
			if (client.enqueue(frame)) queued++;
			else { client.close(); closed++; }
		}
		return new Fanout(true, queued, closed);
	}

	@PreDestroy
	public void shutdown() {
		List<Client> clients;
		synchronized (this) { stopping = true; clients = List.copyOf(owned); }
		clients.forEach(Client::close);
		writers.shutdown();
		closers.shutdown();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		try {
			writers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			closers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
	}

	private record Frame(String name, UUID applicationId, UUID environmentId, String level, String json, int bytes) {
		Frame(String name, UUID applicationId, UUID environmentId, String level, String json) {
			this(name, applicationId, environmentId, level, json, json.getBytes(StandardCharsets.UTF_8).length + framing(name));
		}
	}

	private final class Client implements Subscription {
		private final UUID actorId;
		private final Filters filters;
		private final Authorization authorization;
		private final Sink sink;
		private final ArrayDeque<Frame> queue = new ArrayDeque<>();
		private final Instant opened = clock.instant();
		private Instant lastHeartbeat = opened;
		private boolean active;
		private boolean closed;
		private boolean writerFinished;
		private boolean transportFinished;
		private int queuedBytes;
		private int inFlight;
		private Thread writer;

		Client(UUID actorId, Filters filters, Authorization authorization, Sink sink) {
			this.actorId = actorId;
			this.filters = filters;
			this.authorization = authorization;
			this.sink = sink;
		}

		synchronized boolean matches(Frame frame) {
			return !closed && (filters.applicationId() == null || filters.applicationId().equals(frame.applicationId()))
					&& (filters.environmentId() == null || filters.environmentId().equals(frame.environmentId()))
					&& (filters.level() == null || filters.level().equals(frame.level()));
		}

		synchronized boolean enqueue(Frame frame) {
			if (closed) return false;
			if (queue.size() + inFlight >= MAX_FRAMES || queuedBytes + frame.bytes > MAX_BYTES) return false;
			queue.addLast(frame);
			queuedBytes += frame.bytes;
			notifyAll();
			return true;
		}

		@Override
		public synchronized void activate() {
			if (!closed) { active = true; notifyAll(); }
		}

		@Override
		public void close() {
			Thread running;
			synchronized (this) {
				if (closed) return;
				closed = true;
				queue.clear();
				queuedBytes = 0;
				inFlight = 0;
				running = writer;
				notifyAll();
			}
			authorization.close();
			if (running != null) running.interrupt();
			try {
				closers.execute(() -> {
					try {
						CompletionStage<Void> completion = sink.close();
						completion.whenComplete((ignored, error) -> { synchronized (Client.this) { transportFinished = true; } releaseIfFinished(); });
					} catch (RuntimeException ex) { /* Keep the slot: native transport completion is unproven. */ }
				});
			} catch (RejectedExecutionException ex) { /* Keep the slot: close work was not admitted. */ }
		}

		void writeLoop() {
			synchronized (this) { writer = Thread.currentThread(); }
			try {
				while (true) {
					List<Frame> batch = new ArrayList<>(DRAIN_QUANTUM);
					boolean heartbeat;
					synchronized (this) {
						if (closed) return;
						if (!active) { waitTick(); continue; }
						Instant now = clock.instant();
						if (!now.isBefore(opened.plus(LIFETIME))) { close(); return; }
						heartbeat = !now.isBefore(lastHeartbeat.plus(HEARTBEAT));
						for (int i = 0; i < DRAIN_QUANTUM && !queue.isEmpty(); i++) {
							Frame frame = queue.removeFirst(); inFlight++; batch.add(frame);
						}
						if (batch.isEmpty() && !heartbeat) { waitTick(); continue; }
					}
					if (!ready()) { close(); return; }
					if (heartbeat) {
						authorization.requireSession();
						authorization.requireExplicitScope();
						synchronized (this) { if (closed) return; lastHeartbeat = clock.instant(); }
						sink.heartbeat();
					}
					for (Frame frame : batch) {
						try {
						authorization.requireSession();
						if (!authorization.mayRead(frame.applicationId(), frame.environmentId())) {
							authorization.requireSession();
							if (filters.applicationId() != null) { close(); return; }
							continue;
						}
						synchronized (this) { if (closed) return; }
						sink.send(frame.name(), frame.json());
						} finally {
							synchronized (this) { if (!closed) { inFlight--; queuedBytes -= frame.bytes; } }
						}
					}
					Thread.sleep(TICK_MILLIS);
				}
			} catch (InterruptedException ex) { Thread.currentThread().interrupt(); close(); }
			catch (Exception ex) { close(); }
			finally {
				synchronized (this) { writerFinished = true; }
				releaseIfFinished();
			}
		}

		private void waitTick() throws InterruptedException { wait(TICK_MILLIS); }

		private void releaseIfFinished() {
			synchronized (this) { if (!writerFinished || !transportFinished) return; }
			synchronized (RealtimeService.this) { owned.remove(this); }
		}
	}
}
