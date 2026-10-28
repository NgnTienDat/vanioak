package com.h.vanioak.modules.realtime.internal.subscription;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.h.vanioak.modules.realtime.api.ErrorCode;
import com.h.vanioak.modules.realtime.api.RealtimeException;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;

import tools.jackson.databind.ObjectMapper;

class RealtimeServiceTest {
	private final UUID actor = UUID.randomUUID();
	private final UUID app = UUID.randomUUID();
	private final UUID env = UUID.randomUUID();
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void disabledAndUnregisteredConsumerNeverAdmitOrAccount() {
		for (boolean enabled : List.of(false, true)) {
			RealtimeService service = new RealtimeService(enabled, () -> false, Clock.systemUTC(), mapper);
			try {
				assertEquals(ErrorCode.NOT_READY, assertThrows(RealtimeException.class,
						() -> service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), new Guard(), new Sink()))
						.getErrorCode());
				assertFalse(service.publishLog(log()).accounted());
			} finally { service.shutdown(); }
		}
	}

	@Test
	void waitsForActivationAndChecksCurrentAuthorizationForEveryFrame() throws Exception {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Guard guard = new Guard();
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(app, env, "ERROR"), guard, sink);
		try {
			assertEquals(1, service.publishLog(log()).queued());
			Thread.sleep(120);
			assertTrue(sink.frames.isEmpty());
			subscription.activate();
			assertTrue(sink.sent.await(2, TimeUnit.SECONDS));
			assertEquals("log", sink.frames.getFirst().name());
			var tree = mapper.readTree(sink.frames.getFirst().json());
			assertEquals(9, tree.size());
			assertEquals("line\nfield: fake", tree.get("message").asString());
			assertEquals(1, guard.sessions);
			guard.allowed.set(false);
			service.publishLog(log());
			Thread.sleep(120);
			assertEquals(1, sink.frames.size());
			assertTrue(guard.closed);
		} finally { subscription.close(); service.shutdown(); }
	}

	@Test
	void incidentViewsHaveExactFieldSetsAndUseInternalScope() throws Exception {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(app, env, "ERROR"), new Guard(), sink);
		try {
			subscription.activate();
			UUID incident = UUID.randomUUID();
			Instant now = Instant.parse("2026-10-26T00:00:00Z");
			service.publishIncident(new RealtimeFacade.IncidentEvent(UUID.randomUUID(), "incident.created", now, "alert", 1, incident, app, env,
					"ERROR", "OPEN", "message", 1L, null, null));
			service.publishIncident(new RealtimeFacade.IncidentEvent(UUID.randomUUID(), "incident.updated", now, "alert", 1, incident, app, env,
					"ERROR", "OPEN", null, 2L, now, null));
			service.publishIncident(new RealtimeFacade.IncidentEvent(UUID.randomUUID(), "incident.resolved", now, "alert", 1, incident, app, env,
					"ERROR", "RESOLVED", null, null, null, now));
			awaitFrames(sink, 3);
			assertEquals(List.of("incident.created", "incident.updated", "incident.resolved"),
					sink.frames.stream().map(Frame::name).toList());
			assertEquals(7, mapper.readTree(sink.frames.get(0).json()).size());
			assertEquals(3, mapper.readTree(sink.frames.get(1).json()).size());
			assertEquals(3, mapper.readTree(sink.frames.get(2).json()).size());
			assertFalse(mapper.readTree(sink.frames.get(1).json()).has("application_id"));
		} finally { subscription.close(); service.shutdown(); }
	}

	@Test
	void blockedWriterAndUnconfirmedTransportKeepAdmissionSlot() throws Exception {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Sink blocked = new Sink();
		blocked.block = new CountDownLatch(1);
		var held = service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), new Guard(), blocked);
		List<RealtimeFacade.Subscription> others = new ArrayList<>();
		try {
			held.activate();
			service.publishLog(log());
			assertTrue(blocked.entered.await(2, TimeUnit.SECONDS));
			held.close();
			for (int i = 0; i < 3; i++) others.add(service.subscribe(actor,
					new RealtimeFacade.Filters(null, null, null), new Guard(), new Sink()));
			assertEquals(ErrorCode.OVERLOADED, assertThrows(RealtimeException.class,
					() -> service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), new Guard(), new Sink()))
					.getErrorCode());
			blocked.block.countDown();
			Thread.sleep(100);
			assertEquals(ErrorCode.OVERLOADED, assertThrows(RealtimeException.class,
					() -> service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), new Guard(), new Sink()))
					.getErrorCode());
			blocked.terminated.complete(null);
		} finally {
			blocked.block.countDown(); blocked.terminated.complete(null);
			held.close(); others.forEach(RealtimeFacade.Subscription::close); service.shutdown();
		}
	}

	@Test
	void idleHeartbeatRechecksSessionAndExplicitScope() throws Exception {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-26T00:00:00Z"));
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneId.of("UTC"); }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		RealtimeService service = new RealtimeService(true, () -> true, clock, mapper);
		Guard guard = new Guard();
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(app, env, null), guard, sink);
		try {
			subscription.activate();
			now.set(now.get().plusSeconds(16));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (guard.sessions == 0 && System.nanoTime() < deadline) Thread.sleep(10);
			assertTrue(guard.sessions > 0);
			assertTrue(guard.explicitChecks > 0);
			guard.allowed.set(false);
			now.set(now.get().plusSeconds(16));
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!guard.closed && System.nanoTime() < deadline) Thread.sleep(10);
			assertTrue(guard.closed);
		} finally { subscription.close(); sink.terminated.complete(null); service.shutdown(); }
	}

	@Test
	void broadStreamSkipsRevokedAppAndAcceptsLaterGrant() throws Exception {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Guard guard = new Guard();
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), guard, sink);
		try {
			subscription.activate();
			guard.allowed.set(false);
			service.publishLog(log());
			Thread.sleep(100);
			assertTrue(sink.frames.isEmpty());
			assertFalse(guard.closed);
			guard.allowed.set(true);
			service.publishLog(log());
			assertTrue(sink.sent.await(2, TimeUnit.SECONDS));
			assertEquals(1, sink.frames.size());
		} finally { subscription.close(); sink.terminated.complete(null); service.shutdown(); }
	}

	@Test
	void queuedFrameLimitClosesOnlySlowClientBeforeActivation() {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Guard slow = new Guard();
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), slow, sink);
		try {
			for (int i = 0; i < 64; i++) assertEquals(1, service.publishLog(log()).queued());
			var overflow = service.publishLog(log());
			assertTrue(overflow.accounted());
			assertEquals(0, overflow.queued());
			assertEquals(1, overflow.closed());
			assertTrue(slow.closed);
			assertEquals(0, service.publishLog(log()).closed());
			assertTrue(sink.frames.isEmpty());
		} finally { subscription.close(); sink.terminated.complete(null); service.shutdown(); }
	}

	@Test
	void serializedByteLimitRejectsWholeFrameAndClosesFullClient() {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Guard guard = new Guard();
		Sink sink = new Sink();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), guard, sink);
		String large = "x".repeat(600_000);
		try {
			var event = new RealtimeFacade.LogEvent(UUID.randomUUID(), app, env, "127.0.0.1", "ERROR",
					large, Instant.parse("2026-10-26T00:00:00Z"), "trace", Map.of());
			assertEquals(1, service.publishLog(event).queued());
			assertEquals(1, service.publishLog(event).closed());
			assertTrue(guard.closed);
			assertTrue(sink.frames.isEmpty());
			var oversized = new RealtimeFacade.LogEvent(UUID.randomUUID(), app, env, "127.0.0.1", "ERROR",
					"x".repeat(1_048_576), Instant.parse("2026-10-26T00:00:00Z"), "trace", Map.of());
			assertFalse(service.publishLog(oversized).accounted());
		} finally { subscription.close(); sink.terminated.complete(null); service.shutdown(); }
	}

	private void awaitFrames(Sink sink, int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (sink.frames.size() < count && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(count, sink.frames.size());
	}

	@Test
	void completeFrameBoundaryIncludesSseFramingBeforeZeroSubscriberAccounting() {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		var prototype = log();
		Map<String, Object> projection = Map.of("event_id", prototype.eventId(), "application_id", app,
				"environment_id", env, "host_ip", prototype.hostIp(), "level", prototype.level(),
				"message", "", "timestamp", prototype.timestamp().toString(), "trace_id", prototype.traceId(), "metadata", Map.of());
		int overhead = mapper.writeValueAsBytes(projection).length + "event:log\ndata:\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		String exact = "x".repeat(1_048_576 - overhead);
		try {
			assertTrue(service.publishLog(new RealtimeFacade.LogEvent(prototype.eventId(), app, env, prototype.hostIp(),
					prototype.level(), exact, prototype.timestamp(), prototype.traceId(), Map.of())).accounted());
			assertFalse(service.publishLog(new RealtimeFacade.LogEvent(prototype.eventId(), app, env, prototype.hostIp(),
					prototype.level(), exact + "x", prototype.timestamp(), prototype.traceId(), Map.of())).accounted());
		} finally { service.shutdown(); }
	}

	@Test
	void inFlightWriterFramesStillCountTowardClientAdmissionBudget() throws Exception {
		RealtimeService service = new RealtimeService(true, () -> true, Clock.systemUTC(), mapper);
		Sink sink = new Sink(); sink.block = new CountDownLatch(1); Guard guard = new Guard();
		var subscription = service.subscribe(actor, new RealtimeFacade.Filters(null, null, null), guard, sink);
		try {
			subscription.activate(); service.publishLog(log());
			assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
			for (int i = 0; i < 63; i++) assertEquals(1, service.publishLog(log()).queued());
			assertEquals(1, service.publishLog(log()).closed()); assertTrue(guard.closed);
		} finally { sink.block.countDown(); sink.terminated.complete(null); subscription.close(); service.shutdown(); }
	}

	private RealtimeFacade.LogEvent log() {
		return new RealtimeFacade.LogEvent(UUID.randomUUID(), app, env, "127.0.0.1", "ERROR",
				"line\nfield: fake", Instant.parse("2026-10-26T00:00:00Z"), "trace", Map.of());
	}

	private static final class Guard implements RealtimeFacade.Authorization {
		final AtomicBoolean allowed = new AtomicBoolean(true);
		volatile boolean closed;
		volatile int sessions;
		volatile int explicitChecks;
		@Override public void requireSession() { sessions++; if (closed) throw new IllegalStateException(); }
		@Override public void requireExplicitScope() { explicitChecks++; if (!allowed.get()) throw new IllegalStateException(); }
		@Override public boolean mayRead(UUID applicationId, UUID environmentId) { return allowed.get(); }
		@Override public void close() { closed = true; }
	}

	private record Frame(String name, String json) { }
	private static final class Sink implements RealtimeFacade.Sink {
		final List<Frame> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
		final CountDownLatch sent = new CountDownLatch(1);
		final CountDownLatch entered = new CountDownLatch(1);
		final CompletableFuture<Void> terminated = new CompletableFuture<>();
		CountDownLatch block;
		@Override public void send(String eventName, String json) throws InterruptedException {
			entered.countDown();
			if (block != null) block.await();
			frames.add(new Frame(eventName, json)); sent.countDown();
		}
		@Override public void heartbeat() { }
		@Override public CompletableFuture<Void> close() { return terminated; }
	}
}
