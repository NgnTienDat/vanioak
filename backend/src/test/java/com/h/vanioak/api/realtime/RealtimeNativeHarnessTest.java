package com.h.vanioak.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class RealtimeNativeHarnessTest {
	final ObjectMapper mapper = new ObjectMapper();
	@TempDir Path temp;
	final String run = UUID.randomUUID().toString();
	final Instant now = Instant.now();
	final Predicate<Path> released = path -> !path.equals(RealtimeNativeHappyPathIT.FIXED_FENCE) && Files.exists(path);
	final RealtimeNativeHappyPathIT.ExpectedRegistration registration = new RealtimeNativeHappyPathIT.ExpectedRegistration("expected-tag", 7, "native-client-name");
	RealtimeNativeHappyPathIT.RegistrationView registrationView(boolean visible) {
		return new RealtimeNativeHappyPathIT.RegistrationView(mapper.valueToTree(visible ? List.of(Map.of(
				"consumer_tag", "expected-tag", "queue", Map.of("name", "realtime.queue", "vhost", "processing-test"),
				"channel_details", Map.of("number", 7, "connection_name", "server-connection"),
				"exclusive", true, "ack_required", true, "active", true, "prefetch_count", 1)) : List.of()),
				mapper.valueToTree(visible ? List.of(Map.of("name", "server-connection", "vhost", "processing-test",
						"client_properties", Map.of("connection_name", "native-client-name"))) : List.of()));
	}
	tools.jackson.databind.JsonNode poll(RealtimeNativeHappyPathIT.RegistrationRead read, BooleanSupplier nativeCurrent,
			AtomicLong clock, Map<String, Object> diagnostics) throws Exception {
		return RealtimeNativeHappyPathIT.awaitRegistration(registration, read, nativeCurrent, clock::get,
				clock::addAndGet, diagnostics::putAll);
	}
	@Test void immediatelyVisibleExactRegistrationSucceeds() throws Exception {
		var diagnostics = new LinkedHashMap<String, Object>();
		assertEquals(1, poll(end -> registrationView(true), () -> true, new AtomicLong(), diagnostics).size());
		assertEquals("REGISTERED", diagnostics.get("reason")); assertEquals(1, diagnostics.get("polls"));
	}
	@Test void delayedVisibilityUsesOneFixedDeadline() throws Exception {
		AtomicInteger calls = new AtomicInteger(); AtomicLong clock = new AtomicLong();
		List<Long> deadlines = new ArrayList<>(); var diagnostics = new LinkedHashMap<String, Object>();
		poll(end -> { deadlines.add(end); return registrationView(calls.incrementAndGet() >= 3); }, () -> true, clock, diagnostics);
		assertEquals(3, calls.get()); assertEquals(TimeUnit.MILLISECONDS.toNanos(200), clock.get());
		assertEquals(List.of(TimeUnit.SECONDS.toNanos(10)), deadlines.stream().distinct().toList());
	}
	@Test void connectionMetadataMustAlsoConverge() throws Exception {
		AtomicInteger calls = new AtomicInteger(); var visible = registrationView(true);
		poll(end -> calls.incrementAndGet() == 1 ? new RealtimeNativeHappyPathIT.RegistrationView(visible.consumers(), mapper.createArrayNode()) : visible,
				() -> true, new AtomicLong(), new LinkedHashMap<>());
		assertEquals(2, calls.get());
	}
	@Test void wrongTagChannelFlagsOrPrefetchCannotPass() {
		for (String field : List.of("consumer_tag", "exclusive", "ack_required", "active", "prefetch_count", "number")) {
			var view = registrationView(true);
			var object = (tools.jackson.databind.node.ObjectNode) (field.equals("number") ? view.consumers().get(0).get("channel_details") : view.consumers().get(0));
			if (field.equals("consumer_tag")) object.put(field, "wrong-tag");
			else if (field.equals("number") || field.equals("prefetch_count")) object.put(field, 9);
			else object.put(field, false);
			var diagnostics = new LinkedHashMap<String, Object>();
			assertThrows(AssertionError.class, () -> poll(end -> view, () -> true, new AtomicLong(), diagnostics));
			assertEquals("WRONG_MANAGEMENT_REGISTRATION", diagnostics.get("reason"));
		}
	}
	@Test void wrongConnectionIdentityCannotPass() {
		var view = registrationView(true);
		((tools.jackson.databind.node.ObjectNode) view.connections().get(0).get("client_properties")).put("connection_name", "wrong-native-client");
		assertThrows(AssertionError.class, () -> poll(end -> view, () -> true, new AtomicLong(), new LinkedHashMap<>()));
	}
	@Test void duplicateConsumerAndWrongScopeCannotPass() {
		var duplicate = registrationView(true);
		((tools.jackson.databind.node.ArrayNode) duplicate.consumers()).add(duplicate.consumers().get(0));
		assertThrows(AssertionError.class, () -> poll(end -> duplicate, () -> true, new AtomicLong(), new LinkedHashMap<>()));
		var wrong = registrationView(true);
		((tools.jackson.databind.node.ObjectNode) wrong.consumers().get(0).get("queue")).put("vhost", "other");
		assertThrows(AssertionError.class, () -> poll(end -> wrong, () -> true, new AtomicLong(), new LinkedHashMap<>()));
	}
	@Test void permanentlyMissingConsumerTimesOutWithNativeState() {
		AtomicLong clock = new AtomicLong(); var diagnostics = new LinkedHashMap<String, Object>();
		assertThrows(AssertionError.class, () -> poll(end -> registrationView(false), () -> true, clock, diagnostics));
		assertEquals(TimeUnit.SECONDS.toNanos(10), clock.get()); assertEquals(100, diagnostics.get("polls"));
		assertEquals("MANAGEMENT_VISIBILITY_TIMEOUT", diagnostics.get("reason")); assertEquals(true, diagnostics.get("native_current"));
	}
	@Test void nativeDisappearanceFailsWithoutWaitingForManagement() {
		AtomicInteger reads = new AtomicInteger(); var diagnostics = new LinkedHashMap<String, Object>();
		assertThrows(AssertionError.class, () -> poll(end -> { reads.incrementAndGet(); return registrationView(true); },
				() -> false, new AtomicLong(), diagnostics));
		assertEquals(0, reads.get()); assertEquals("NATIVE_REGISTRATION_LOST", diagnostics.get("reason"));
	}
	@Test void readinessLostDuringReadCannotPass() {
		AtomicInteger checks = new AtomicInteger(); var diagnostics = new LinkedHashMap<String, Object>();
		assertThrows(AssertionError.class, () -> poll(end -> registrationView(true), () -> checks.incrementAndGet() == 1,
				new AtomicLong(), diagnostics));
		assertEquals("NATIVE_REGISTRATION_LOST", diagnostics.get("reason"));
	}
	@Test void aLateSuccessfulResponseDoesNotExtendDeadline() {
		AtomicLong clock = new AtomicLong(); var diagnostics = new LinkedHashMap<String, Object>();
		assertThrows(AssertionError.class, () -> poll(end -> { clock.set(end); return registrationView(true); }, () -> true, clock, diagnostics));
		assertEquals("MANAGEMENT_VISIBILITY_TIMEOUT", diagnostics.get("reason"));
	}
	@Test void queryFailureIsNotRetriedOrLeaked() {
		AtomicInteger reads = new AtomicInteger(); var diagnostics = new LinkedHashMap<String, Object>();
		var error = assertThrows(AssertionError.class, () -> poll(end -> { reads.incrementAndGet(); throw new java.io.IOException("sensitive-body"); },
				() -> true, new AtomicLong(), diagnostics));
		assertEquals(1, reads.get()); org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("sensitive-body"));
		assertEquals("MANAGEMENT_QUERY_FAILED", diagnostics.get("reason"));
	}
	@Test void queryInterruptionPreservesCancellation() {
		try {
			assertThrows(AssertionError.class, () -> poll(end -> { throw new InterruptedException(); }, () -> true,
					new AtomicLong(), new LinkedHashMap<>()));
			org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
		} finally { Thread.interrupted(); }
	}

	Path fresh() throws Exception {
		Path directory = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
		String nonce = UUID.randomUUID().toString();
		var base = new java.util.LinkedHashMap<String, Object>(Map.of("run_id", run, "nonce", nonce,
				"fence_path", RealtimeNativeHappyPathIT.FIXED_FENCE.toString(), "evidence_directory", directory.toString(),
				"at", now.toEpochMilli() / 1000.0));
		Files.write(directory.resolve("wiring-preparation.json"), mapper.writeValueAsBytes(base));
		base.put("selector", "RealtimeNativeHappyPathIT#oneExclusiveConsumerDeliversOnlyAuthorizedNormalEvents");
		Files.write(directory.resolve("one-shot.marker"), mapper.writeValueAsBytes(base));
		Files.write(directory.resolve("preflight-before.json"), mapper.writeValueAsBytes(Map.of("status", "PASS", "at_epoch", now.toEpochMilli() / 1000.0)));
		return directory;
	}
	void validate(Path directory) {
		RealtimeNativeHappyPathIT.validateWiring(directory, RealtimeNativeHappyPathIT.FIXED_FENCE, run, now, released);
	}
	void change(Path directory, String file, String field, Object value) throws Exception {
		var record = mapper.readTree(Files.readAllBytes(directory.resolve(file)));
		((tools.jackson.databind.node.ObjectNode) record).set(field, mapper.valueToTree(value));
		Files.write(directory.resolve(file), mapper.writeValueAsBytes(record));
	}
	@Test void distinctFreshEvidenceDirectoriesUseTheSameApprovedFence() throws Exception {
		Path first = fresh(), second = fresh();
		assertNotEquals(first, second); assertNotEquals(first, RealtimeNativeHappyPathIT.FIXED_FENCE.getParent());
		validate(first); validate(second);
	}
	@Test void arbitraryRelativeAndTypoFencePathsFailClosed() throws Exception {
		Path directory = fresh();
		for (Path path : List.of(directory.resolve("realtime.fence"), Path.of("realtime.fence"),
				Path.of("D:/Web System Development/vanioak.local/realtime-native-happy-20261026/realtime.fence")))
			assertThrows(IllegalStateException.class, () -> RealtimeNativeHappyPathIT.validateWiring(directory, path, run, now, released));
	}
	@Test void armedFenceCannotBeBypassedByFreshEvidence() throws Exception {
		Path directory = fresh();
		assertThrows(IllegalStateException.class, () -> RealtimeNativeHappyPathIT.validateWiring(directory,
				RealtimeNativeHappyPathIT.FIXED_FENCE, run, now, path -> path.equals(RealtimeNativeHappyPathIT.FIXED_FENCE) || Files.exists(path)));
	}
	@Test void unpreparedAndHistoricalEvidenceDirectoriesFailClosed() throws Exception {
		Path empty = Files.createDirectory(temp.resolve("empty"));
		assertThrows(IllegalStateException.class, () -> validate(empty));
		assertThrows(IllegalStateException.class, () -> validate(RealtimeNativeHappyPathIT.FIXED_FENCE.getParent()));
	}
	@Test void priorOutputsCannotBeReused() throws Exception {
		for (String file : List.of("REPORT.md", "scenario-state.json", "maven-exit.txt", "expected-inputs.json", "sse-0.txt", "sse-1.txt")) {
			Path directory = fresh(); Files.writeString(directory.resolve(file), "old");
			assertThrows(IllegalStateException.class, () -> validate(directory));
		}
	}
	@Test void wrongRunNonceScopeAndSelectorFailClosed() throws Exception {
		for (String field : List.of("run_id", "nonce", "fence_path", "evidence_directory", "selector")) {
			Path directory = fresh(); change(directory, "one-shot.marker", field, "unapproved");
			assertThrows(IllegalStateException.class, () -> validate(directory));
		}
	}
	@Test void staleFutureAndMalformedMarkersFailClosed() throws Exception {
		for (double offset : List.of(-211.0, 6.0)) {
			Path directory = fresh(); change(directory, "one-shot.marker", "at", now.toEpochMilli() / 1000.0 + offset);
			assertThrows(IllegalStateException.class, () -> validate(directory));
		}
		Path directory = fresh(); Files.writeString(directory.resolve("one-shot.marker"), "invalid-json");
		assertThrows(IllegalStateException.class, () -> validate(directory));
	}
	@Test void failedPreflightMissingAndOversizedMarkersFailClosed() throws Exception {
		Path failed = fresh(); change(failed, "preflight-before.json", "status", "FAIL");
		assertThrows(IllegalStateException.class, () -> validate(failed));
		Path missing = fresh(); Files.delete(missing.resolve("one-shot.marker"));
		assertThrows(IllegalStateException.class, () -> validate(missing));
		Path large = fresh(); Files.writeString(large.resolve("one-shot.marker"), "x".repeat(4097));
		assertThrows(IllegalStateException.class, () -> validate(large));
	}
	@Test void parsesExactLogAndHeartbeatWithoutStartingSpringOrNetwork() {
		var frame = RealtimeNativeHappyPathIT.parse("event:log\ndata:{\"message\":\"line\\nvalue\"}\n\n", mapper);
		assertEquals("log", frame.name()); assertEquals("line\nvalue", frame.data().get("message").asString());
		assertNull(RealtimeNativeHappyPathIT.parse(":heartbeat\r\n\r\n", mapper));
	}
	@Test void unexpectedFieldsAndDuplicateDataFailClosed() {
		assertThrows(IllegalArgumentException.class, () -> RealtimeNativeHappyPathIT.parse("id:unexpected\ndata:{}\n\n", mapper));
		assertThrows(IllegalArgumentException.class, () -> RealtimeNativeHappyPathIT.parse("event:log\ndata:{}\ndata:{}\n\n", mapper));
	}
}
