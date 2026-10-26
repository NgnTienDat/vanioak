package com.h.vanioak.modules.processing.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProcessingObserverTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ProcessingObserver.Scope SCOPE = new ProcessingObserver.Scope("aabbccdd", new UUID(0, 1), new UUID(0, 2));

    static String trace(int index) { return "aabbccdd" + String.format("%020x%04x", index / 100, index % 100); }
    static String row(int index, String level) {
        return "{\"event_id\":\"" + new UUID(0, index + 1) + "\",\"trace_id\":\"" + trace(index) + "\",\"level\":\"" + level + "\"}";
    }
    static byte[] event(int index, String level, boolean critical) {
        return ("{\"event_id\":\"" + new UUID(0, index + 1) + "\",\"event_type\":\"" + (critical ? "critical.log" : "processed.log")
                + "\",\"payload\":{\"event_id\":\"" + new UUID(0, index + 1) + "\",\"trace_id\":\"" + trace(index)
                + "\",\"application_id\":\"" + SCOPE.application() + "\",\"environment_id\":\"" + SCOPE.environment()
                + "\",\"level\":\"" + level + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test void allQueriesAreExactScopedAndBounded() {
        assertTrue(ProcessingObserver.aggregateQuery(SCOPE).contains("uniqExact(event_id)"));
        for (String sql : List.of(ProcessingObserver.aggregateQuery(SCOPE), ProcessingObserver.sampleQuery(SCOPE), ProcessingObserver.finalQuery(SCOPE))) {
            assertTrue(sql.contains(SCOPE.application().toString()));
            assertTrue(sql.contains(SCOPE.environment().toString()));
            assertTrue(sql.contains("startsWith(trace_id, 'aabbccdd')"));
            assertTrue(sql.contains("max_threads=1,max_memory_usage=67108864,max_execution_time=5"));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProcessingObserver.Scope("bad'", SCOPE.application(), SCOPE.environment()));
    }

    @Test void visibilityPreservesFirstObservationAndComputesArithmeticMean() {
        var samples = new HashMap<String, ProcessingObserver.Visibility>();
        String row = "{\"event_id\":\"one\",\"received_at\":\"2026-10-12 00:00:00.123\"}";
        var first = ProcessingObserver.recordVisibility(samples, row, Instant.parse("2026-10-12T00:00:01.123Z"));
        assertEquals(List.of(1000L), first.stream().map(ProcessingObserver.Visibility::latency_ms).toList());
        var persisted = JSON.readTree(JSON.writeValueAsString(first)).get(0);
        assertEquals("one", persisted.path("event_id").asString());
        assertEquals("2026-10-12T00:00:00.123Z", persisted.path("received_at").asString());
        assertEquals("2026-10-12T00:00:01.123Z", persisted.path("observed_at").asString());
        assertEquals(1000L, persisted.path("latency_ms").asLong());
        assertEquals(List.of(), ProcessingObserver.recordVisibility(samples, row, Instant.parse("2026-10-12T00:00:09Z")));
        var stats = ProcessingObserver.latencySummary(List.of(1000L, 2000L, 6000L));
        assertEquals(9000L, stats.get("sum_ms")); assertEquals(3000d, stats.get("mean_ms"));
        assertEquals(6000L, stats.get("p95"));
        assertNull(ProcessingObserver.latencySummary(List.of()).get("mean_ms"));
        String late = "{\"event_id\":\"late\",\"received_at\":\"2026-10-12 00:00:00.123\"}";
        assertEquals(List.of(8877L), ProcessingObserver.recordVisibility(samples, row + "\n" + late, Instant.parse("2026-10-12T00:00:09Z")).stream().map(ProcessingObserver.Visibility::latency_ms).toList());
        assertEquals(1000L, samples.get("one").latency_ms());
    }

    @Test void phasesUseReceivedAtAtExactBoundariesIncludingDrainObservations() {
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        var samples = new HashMap<String, ProcessingObserver.Visibility>();
        // All first seen in drain: observation time must never choose the cohort.
        String rows = visibilityRow("before", start.minusMillis(1)) + "\n"
                + visibilityRow("start", start) + "\n"
                + visibilityRow("warm", start.plusMillis(19_999)) + "\n"
                + visibilityRow("measurement", start.plusSeconds(20)) + "\n"
                + visibilityRow("last", start.plusMillis(319_999)) + "\n"
                + visibilityRow("end", start.plusSeconds(320));
        ProcessingObserver.recordVisibility(samples, rows, start.plusSeconds(321));
        var phases = ProcessingObserver.visibilityPhases(samples.values(), start, start.plusSeconds(321), 0, false, null);
        var warm = (Map<?, ?>) phases.get("warmup");
        var measured = (Map<?, ?>) phases.get("measurement");
        assertEquals(2, warm.get("sample_count")); assertEquals(622001L, warm.get("sum_ms"));
        assertEquals(311000.5, warm.get("mean_ms"));
        assertEquals(2, measured.get("sample_count")); assertEquals(302001L, measured.get("sum_ms"));
        assertEquals(151000.5, measured.get("mean_ms"));
        assertEquals(1001L, measured.get("p50")); assertEquals(301000L, measured.get("p95"));
        assertEquals(301000L, measured.get("p99")); assertEquals(2L, phases.get("outside_phase_samples"));
        assertEquals(false, phases.get("incomplete_evidence"));
        assertTrue(phases.get("clock_alignment").toString().startsWith("UNVERIFIED"));
    }

    @Test void warmupSeenDuringMeasurementAndMeasurementSeenDuringDrainKeepOwnCohorts() {
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        var samples = new HashMap<String, ProcessingObserver.Visibility>();
        ProcessingObserver.recordVisibility(samples, visibilityRow("warm", start.plusSeconds(19)), start.plusSeconds(21));
        ProcessingObserver.recordVisibility(samples, visibilityRow("measurement", start.plusSeconds(319)), start.plusSeconds(322));
        assertEquals(List.of(), ProcessingObserver.recordVisibility(samples,
                visibilityRow("measurement", start.plusSeconds(319)), start.plusSeconds(330)));
        var phases = ProcessingObserver.visibilityPhases(samples.values(), start, start.plusSeconds(322), 0, false, null);
        assertEquals(2000d, ((Map<?, ?>) phases.get("warmup")).get("mean_ms"));
        assertEquals(3000d, ((Map<?, ?>) phases.get("measurement")).get("mean_ms"));
        assertEquals(2, samples.size());
    }

    @Test void missingInvalidAndChangedSampleTimestampsFailWithoutInventedLatencies() {
        var samples = new HashMap<String, ProcessingObserver.Visibility>();
        Instant observed = Instant.parse("2026-10-12T00:00:01Z");
        assertThrows(RuntimeException.class, () -> ProcessingObserver.recordVisibility(samples, "{\"event_id\":\"one\"}", observed));
        assertThrows(RuntimeException.class, () -> ProcessingObserver.recordVisibility(samples, visibilityRow("one", observed.plusMillis(1)), observed));
        assertTrue(samples.isEmpty());
        ProcessingObserver.recordVisibility(samples, visibilityRow("one", observed.minusMillis(123)), observed);
        assertEquals(123L, samples.get("one").latency_ms());
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.recordVisibility(samples, visibilityRow("one", observed), observed));
        assertEquals(123L, samples.get("one").latency_ms());
    }

    @Test void partialGapsMissingStartAndUnfinishedMeasurementAreExplicit() {
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        var samples = List.of(new ProcessingObserver.Visibility("one", start.plusSeconds(21).toString(), start.plusSeconds(22).toString(), 1000));
        for (var phases : List.of(
                ProcessingObserver.visibilityPhases(samples, start, start.plusSeconds(321), 1, false, null),
                ProcessingObserver.visibilityPhases(samples, start, start.plusSeconds(321), 0, true, null),
                ProcessingObserver.visibilityPhases(samples, start, start.plusSeconds(319), 0, false, null),
                ProcessingObserver.visibilityPhases(samples, start, start.plusSeconds(321), 0, false, "IOException"))) {
            assertEquals(true, phases.get("incomplete_evidence"));
            assertEquals(1000d, ((Map<?, ?>) phases.get("measurement")).get("mean_ms"));
        }
        var missing = ProcessingObserver.visibilityPhases(samples, null, start.plusSeconds(321), 0, false, null);
        assertEquals(true, missing.get("scenario_timestamp_missing"));
        assertNull(((Map<?, ?>) missing.get("measurement")).get("mean_ms"));
        assertEquals(0, ((Map<?, ?>) missing.get("measurement")).get("sample_count"));
        assertEquals(true, missing.get("incomplete_evidence"));
    }

    @Test void scenarioStartUsesScopedK6TimestampWithoutFallback(@TempDir Path directory) throws Exception {
        Path points = directory.resolve("timing.ndjson");
        Files.writeString(points, "{\"type\":\"Point\",\"metric\":\"http_completion_ms\",\"data\":{\"time\":\"2026-10-12T00:00:01Z\"}}\n");
        assertNull(ProcessingObserver.scenarioStart(points, SCOPE.run()));
        Instant start = Instant.parse("2026-10-12T00:00:00Z");
        String timing = "{\"type\":\"Point\",\"metric\":\"request_timing_ms\",\"data\":{\"tags\":{\"trace_prefix\":\"aabbccdd00000000000000000000\",\"scenario_start_ms\":\"" + start.toEpochMilli() + "\"}}}\n";
        Files.writeString(points, timing + timing);
        assertEquals(start, ProcessingObserver.scenarioStart(points, SCOPE.run()));
        Files.writeString(points, timing + timing.replace(Long.toString(start.toEpochMilli()), Long.toString(start.toEpochMilli() + 1)));
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.scenarioStart(points, SCOPE.run()));
        Files.writeString(points, timing.replace("aabbccdd", "11223344"));
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.scenarioStart(points, SCOPE.run()));
        Files.writeString(points, timing.replace(Long.toString(start.toEpochMilli()), ""));
        assertThrows(RuntimeException.class, () -> ProcessingObserver.scenarioStart(points, SCOPE.run()));
    }

    private static String visibilityRow(String id, Instant received) {
        return "{\"event_id\":\"" + id + "\",\"received_at\":\"" + received.toString().replace('T', ' ').replace("Z", "") + "\"}";
    }

    @Test void finalReconciliationRequiresEveryRouteAndExactTraceMapping(@TempDir Path directory) throws Exception {
        var stored = new HashMap<UUID, ProcessingObserver.Stored>();
        ProcessingObserver.acceptStored(stored, JSON.readTree(row(0, "INFO")), SCOPE);
        ProcessingObserver.acceptStored(stored, JSON.readTree(row(1, "ERROR")), SCOPE);
        ProcessingObserver.acceptStored(stored, JSON.readTree(row(0, "INFO")), SCOPE);
        assertEquals(2, stored.size());
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.acceptStored(stored, JSON.readTree(row(0, "WARN")), SCOPE));
        var state = new ProcessingObserver.State();
        for (int i : List.of(1, 0, 1)) state.record(new ProcessingObserver.Event(new UUID(0, i + 1), trace(i), false, i));
        Path summary = directory.resolve("summary.json");
        Files.writeString(summary, "{\"configuration\":{\"run_id\":\"aabbccdd\"},\"measurements\":{\"accepted_logs\":2}}");
        var report = ProcessingObserver.reconcile(stored, state, new HashSet<>(Set.of(trace(0), trace(1))), true, summary, SCOPE.run());
        assertEquals(false, report.get("reconciled_counts"));
        state.record(new ProcessingObserver.Event(new UUID(0, 2), trace(1), true, 3));
        report = ProcessingObserver.reconcile(stored, state, new HashSet<>(Set.of(trace(0), trace(1))), true, summary, SCOPE.run());
        assertEquals(true, report.get("reconciled_counts")); assertEquals(1L, report.get("duplicate_event_copies"));
        report = ProcessingObserver.reconcile(stored, state, new HashSet<>(Set.of(trace(0))), true, directory.resolve("absent"), SCOPE.run());
        assertEquals(List.of(trace(1)), report.get("http_uncertain_stored_traces"));
        assertEquals(false, report.get("accepted_to_stored_reconciled"));
        assertEquals(false, ProcessingObserver.reconcile(stored, state, new HashSet<>(Set.of(trace(0), trace(1))), false, summary, SCOPE.run()).get("reconciled_counts"));
    }

    @Test void journalBatchesDurabilityBeforeIndividualAcksAndSupportsReplay(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("events.tsv");
        var state = new ProcessingObserver.State(); var acks = new AtomicInteger();
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                var journal = new ProcessingObserver.Journal(file, SCOPE, state, tag -> {
                    assertTrue(Files.size(path) > 0); acks.incrementAndGet();
                }, new AtomicReference<>())) {
            for (int i = 0; i < 63; i++) journal.append("realtime.queue", event(i, "INFO", false), i);
            assertEquals(0, acks.get()); assertEquals(0, journal.forceCount);
            journal.append("realtime.queue", event(63, "INFO", false), 63);
            assertEquals(64, acks.get()); assertEquals(1, journal.forceCount);
            journal.append("realtime.queue", event(0, "INFO", false), 64);
            var stored = new HashMap<UUID, ProcessingObserver.Stored>();
            for (int i = 0; i < 64; i++) ProcessingObserver.acceptStored(stored, JSON.readTree(row(i, "INFO")), SCOPE);
            ProcessingObserver.verifyJournal(path, journal, stored, SCOPE);
            assertEquals(65, acks.get()); assertEquals(1, state.duplicates);
            assertEquals(Files.size(path), journal.forcedBytes);
        }
    }

    @Test void journalForceFailureNeverAcknowledges() throws Exception {
        FileChannel file = mock(FileChannel.class);
        when(file.write(any(ByteBuffer.class))).thenAnswer(call -> { ByteBuffer b = call.getArgument(0); int n = b.remaining(); b.position(b.limit()); return n; });
        doThrow(new IOException("disk unavailable")).when(file).force(true);
        var failure = new AtomicReference<Throwable>(); var acks = new AtomicInteger();
        try (var journal = new ProcessingObserver.Journal(file, SCOPE, new ProcessingObserver.State(), tag -> acks.incrementAndGet(), failure)) {
            journal.append("realtime.queue", event(0, "INFO", false), 1);
            assertThrows(IOException.class, journal::flush);
            assertEquals(0, acks.get()); assertEquals(0, journal.forcedBytes); assertNotNull(failure.get());
        }
    }

    @Test void forceHappensBeforeEveryAck() throws Exception {
        FileChannel file = mock(FileChannel.class);
        var forced = new AtomicInteger(); var acks = new AtomicInteger();
        when(file.write(any(ByteBuffer.class))).thenAnswer(call -> { ByteBuffer b = call.getArgument(0); int n = b.remaining(); b.position(b.limit()); return n; });
        doAnswer(call -> { forced.incrementAndGet(); return null; }).when(file).force(true);
        try (var journal = new ProcessingObserver.Journal(file, SCOPE, new ProcessingObserver.State(), tag -> {
                    assertTrue(forced.get() > 0); acks.incrementAndGet();
                }, new AtomicReference<>())) {
            for (int i = 0; i < 64; i++) journal.append("realtime.queue", event(i, "INFO", false), i);
            assertEquals(64, acks.get()); assertEquals(1, forced.get());
        }
    }

    @Test void journalAckFailureRetainsDurableEvidenceAndStopsFurtherAcks(@TempDir Path directory) throws Exception {
        var failure = new AtomicReference<Throwable>(); var acks = new AtomicInteger();
        try (var journal = new ProcessingObserver.Journal(FileChannel.open(directory.resolve("events"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                SCOPE, new ProcessingObserver.State(), tag -> { acks.incrementAndGet(); throw new IOException("ambiguous ACK"); }, failure)) {
            journal.append("realtime.queue", event(0, "INFO", false), 1);
            assertThrows(IOException.class, journal::flush);
            assertTrue(journal.forcedBytes > 0); journal.flush(); assertEquals(1, acks.get());
        }
    }

    @Test void journalRejectsUnrelatedScopeRouteAndOversizedBodies(@TempDir Path directory) throws Exception {
        var acks = new AtomicInteger();
        try (var journal = new ProcessingObserver.Journal(FileChannel.open(directory.resolve("events"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                SCOPE, new ProcessingObserver.State(), tag -> acks.incrementAndGet(), new AtomicReference<>())) {
            assertThrows(IllegalStateException.class, () -> journal.append("raw.queue", event(0, "INFO", false), 1));
            assertThrows(IllegalStateException.class, () -> journal.append("alert.queue", event(0, "INFO", true), 1));
            assertThrows(IllegalStateException.class, () -> journal.append("realtime.queue", new String(event(0, "INFO", false), StandardCharsets.UTF_8).replace("aabbccdd", "11223344").getBytes(StandardCharsets.UTF_8), 1));
            assertThrows(IllegalStateException.class, () -> journal.append("realtime.queue", new byte[65537], 1));
            assertEquals(0, acks.get());
        }
    }

    @Test void acceptedManifestRejectsDuplicatesAndWrongRun(@TempDir Path directory) throws Exception {
        Path points = directory.resolve("points.json");
        String point = "{\"type\":\"Point\",\"metric\":\"accepted_trace_batches\",\"data\":{\"value\":2,\"tags\":{\"trace_prefix\":\"aabbccdd00000000000000000000\"}}}";
        Files.writeString(points, point);
        assertEquals(Set.of(trace(0), trace(1)), ProcessingObserver.acceptedTraces(points, SCOPE.run()));
        Files.writeString(points, point + "\n" + point);
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.acceptedTraces(points, SCOPE.run()));
        assertThrows(IllegalStateException.class, () -> ProcessingObserver.acceptedTraces(points, "11223344"));
        Files.writeString(points, point + "\n{\"type\":");
        var known = new HashSet<String>();
        assertThrows(Exception.class, () -> ProcessingObserver.acceptedTraces(points, SCOPE.run(), known));
        assertEquals(Set.of(trace(0), trace(1)), known);
    }

    @Test void streamingRejectsTruncatedAndUnboundedRows() throws Exception {
        var rows = new ArrayList<String>();
        ProcessingObserver.readRows(new ByteArrayInputStream("one\ntwo\n".getBytes(StandardCharsets.UTF_8)), rows::add);
        assertEquals(List.of("one", "two"), rows);
        assertThrows(IOException.class, () -> ProcessingObserver.readRows(new ByteArrayInputStream("truncated".getBytes(StandardCharsets.UTF_8)), rows::add));
        assertThrows(IOException.class, () -> ProcessingObserver.readRows(new ByteArrayInputStream("x".repeat(65537).getBytes(StandardCharsets.UTF_8)), rows::add));
    }

    @Test void memoryRejectionsRetryBoundedlyAndRecordGaps() throws Exception {
        var attempts = new AtomicInteger(); var gaps = new ArrayList<Map<String, Object>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet(); byte[] body = (attempt == 1 ? "\r\n\uFEFF Code: 241. DB::Exception: memory pressure" : attempt == 2 ? "{\"exception\":\"Code: 241. DB::Exception: memory pressure\"}" : "{\"distinct_logs\":1}\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt < 3 ? 500 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
        try (var client = HttpClient.newHttpClient()) {
            var rows = new ArrayList<String>();
            ProcessingObserver.queryLines(client, timer, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", rows::add, gaps::add);
            assertEquals(3, attempts.get()); assertEquals(2, gaps.size()); assertEquals(1, rows.size());
            assertEquals(1, gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("measurement_gap"))).count());
            assertEquals(1, gaps.stream().map(g -> g.get("query_observation_id")).distinct().count());
            assertTrue(gaps.stream().allMatch(g -> Boolean.TRUE.equals(g.get("retry_scheduled"))));
            assertTrue(gaps.stream().allMatch(g -> g.get("clickhouse_code").equals(241)));
        } finally { server.stop(0); timer.shutdownNow(); }
    }

    @Test void exhaustedMemoryRetriesDoNotInventSamples() throws Exception {
        var attempts = new AtomicInteger(); var gaps = new ArrayList<Map<String, Object>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); attempts.incrementAndGet();
            byte[] body = "Code: 241. memory pressure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
        try (var client = HttpClient.newHttpClient()) {
            var rows = new ArrayList<String>();
            assertThrows(IOException.class, () -> ProcessingObserver.queryLines(client, timer,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", rows::add, gaps::add));
            assertEquals(3, attempts.get()); assertTrue(rows.isEmpty());
            assertEquals(3, gaps.size());
            assertEquals(1, gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("measurement_gap"))).count());
            assertEquals(2, gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("retry_scheduled"))).count());
            assertEquals(false, gaps.getLast().get("retry_scheduled"));
        } finally { server.stop(0); timer.shutdownNow(); }
    }

    @Test void nonMemoryErrorsAreNotRetried() throws Exception {
        var attempts = new AtomicInteger(); var gaps = new ArrayList<Map<String, Object>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); attempts.incrementAndGet();
            byte[] body = "Code: 60. missing table".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
        try (var client = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> ProcessingObserver.queryLines(client, timer,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", line -> failUnexpectedRow(), gaps::add));
            assertEquals(1, attempts.get()); assertEquals(1, gaps.size());
            assertEquals(60, gaps.getFirst().get("clickhouse_code"));
            assertEquals(true, gaps.getFirst().get("measurement_gap"));
            assertEquals(false, gaps.getFirst().get("retry_scheduled"));
        } finally { server.stop(0); timer.shutdownNow(); }
    }

    @Test void stalledResponseBodyHasFiniteDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0);
            try { Thread.sleep(6000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
        try (var client = HttpClient.newHttpClient()) {
            long start = System.nanoTime(); var gaps = new ArrayList<Map<String, Object>>();
            assertThrows(IOException.class, () -> ProcessingObserver.queryLines(client, timer,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", line -> failUnexpectedRow(), gaps::add));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8)); assertFalse(gaps.isEmpty());
        } finally { server.stop(0); timer.shutdownNow(); }
    }


    @Test void clickhouseCodesRecognizeHeadersAndBoundedBodyFormatsWithoutMatchingEchoedText() {
        for (String body : List.of("Code: 241. DB::Exception: memory", "\r\n\uFEFF  Code: 241. memory",
                "DB::Exception: Code: 241, memory", "{\"code\":241,\"message\":\"memory\"}",
                "{\"exception\":\"Code: 241. DB::Exception: memory\"}")) {
            assertEquals(241, ProcessingObserver.clickhouseCode(null, body));
        }
        assertEquals(241, ProcessingObserver.clickhouseCode(" 241 ", "unrecognized body"));
        assertEquals(60, ProcessingObserver.clickhouseCode("60", "Code: 241. quoted query"));
        assertEquals(241, ProcessingObserver.clickhouseCode("invalid", "Code: 241. memory"));
        for (String body : List.of("proxy error containing Code: 241. elsewhere", "{broken", "", "Code: 2410. other"))
            assertTrue(ProcessingObserver.clickhouseCode(null, body) != 241);
        assertEquals(-1, ProcessingObserver.clickhouseCode(null, "Code: 999999999999999. invalid"));
    }

    @Test void successfulCyclesAndPartialReportsCountOnlyLogicalQueryGaps(@TempDir Path directory) throws Exception {
        long[] counts = {0, 0, 0};
        try (var writer = Files.newBufferedWriter(directory.resolve("evidence.ndjson"))) {
            for (int i = 0; i < 72; i++) ProcessingObserver.recordEvidence(writer, Map.of("persisted_unique", i), counts);
            assertEquals(0, counts[0]);
            ProcessingObserver.recordEvidence(writer, Map.of("measurement_gap", true, "query_attempt_failed", true, "retry_scheduled", true), counts);
            ProcessingObserver.recordEvidence(writer, Map.of("measurement_gap", false, "query_attempt_failed", true, "retry_scheduled", true), counts);
            ProcessingObserver.recordEvidence(writer, Map.of("persisted_unique", 100), counts);
            assertEquals(1, counts[0]); assertEquals(2, counts[1]); assertEquals(2, counts[2]);
            ProcessingObserver.recordEvidence(writer, Map.of("measurement_gap", true, "query_attempt_failed", true, "retry_scheduled", false), counts);
            ProcessingObserver.recordEvidence(writer, Map.of("measurement_gap", false, "reconciliation_gap", true, "query_attempt_failed", true), counts);
            assertEquals(2, counts[0]); assertEquals(4, counts[1]);
        }
        var phases = ProcessingObserver.visibilityPhases(List.of(), Instant.parse("2026-10-12T00:00:00Z"), null, counts[0], true, null);
        assertEquals(2L, phases.get("measurement_gaps"));
        assertEquals(true, phases.get("incomplete_evidence"));
        assertEquals(77, Files.readAllLines(directory.resolve("evidence.ndjson")).size());
    }

    @Test void exceptionHeaderOnHttp200RetriesButPermanentHttpStatusDoesNot() throws Exception {
        for (int status : List.of(200, 403)) {
            var attempts = new AtomicInteger(); var evidence = new ArrayList<Map<String, Object>>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) exchange.getResponseHeaders().add("X-ClickHouse-Exception-Code", "241");
                byte[] body = (attempt == 1 ? "sensitive response omitted" : "{\"distinct_logs\":1}\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(attempt == 1 ? status : 200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
            try (var client = HttpClient.newHttpClient()) {
                var values = new ArrayList<String>();
                if (status == 200) {
                    ProcessingObserver.queryLines(client, timer, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", values::add, evidence::add);
                    assertEquals(2, attempts.get()); assertEquals(1, values.size());
                    int priorErrors = evidence.size();
                    ProcessingObserver.queryLines(client, timer, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", values::add, evidence::add);
                    assertEquals(priorErrors, evidence.size());
                } else {
                    assertThrows(IOException.class, () -> ProcessingObserver.queryLines(client, timer, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", values::add, evidence::add));
                    assertEquals(1, attempts.get()); assertTrue(values.isEmpty());
                }
                assertEquals(1, evidence.size()); assertEquals(241, evidence.getFirst().get("clickhouse_code"));
                assertFalse(JSON.writeValueAsString(evidence).contains("sensitive"));
            } finally { server.stop(0); timer.shutdownNow(); }
        }
    }


    @Test void malformedSuccessBodyFailsOnceWithoutExposingResponseContents() throws Exception {
        var attempts = new AtomicInteger(); var evidence = new ArrayList<Map<String, Object>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); attempts.incrementAndGet();
            byte[] body = "sensitive-response-content\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start(); var timer = Executors.newSingleThreadScheduledExecutor();
        try (var client = HttpClient.newHttpClient()) {
            IOException failure = assertThrows(IOException.class, () -> ProcessingObserver.queryLines(client, timer,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", "SELECT", JSON::readTree, evidence::add));
            assertEquals(1, attempts.get()); assertEquals(1, evidence.size());
            assertEquals(true, evidence.getFirst().get("measurement_gap"));
            assertEquals(false, evidence.getFirst().get("retry_scheduled"));
            assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("sensitive"));
            assertFalse(JSON.writeValueAsString(evidence).contains("sensitive"));
        } finally { server.stop(0); timer.shutdownNow(); }
    }

    private static void failUnexpectedRow() { throw new AssertionError("Failed query must not create samples"); }

    @Test void startupFailureWritesPartialReportWithKnownAcceptedManifest(@TempDir Path directory) throws Exception {
        Path points = directory.resolve("points.json"), report = directory.resolve("report.json");
        Files.writeString(points, "{\"type\":\"Point\",\"metric\":\"accepted_trace_batches\",\"data\":{\"value\":1,\"tags\":{\"trace_prefix\":\"aabbccdd00000000000000000000\"}}}\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m", "-cp",
                System.getProperty("java.class.path"), ProcessingObserver.class.getName());
        process.environment().clear();
        if (System.getenv("SystemRoot") != null) process.environment().put("SystemRoot", System.getenv("SystemRoot"));
        process.environment().putAll(Map.of("PROCESSING_TEST_SCOPE_APPROVED", "true", "RUN_ID", SCOPE.run(),
                "PROCESSING_OBSERVER_APPLICATION_ID", SCOPE.application().toString(), "PROCESSING_OBSERVER_ENVIRONMENT_ID", SCOPE.environment().toString(),
                "OBSERVE_SECONDS", "1", "PROCESSING_OBSERVER_OUTPUT", report.toString(), "K6_POINTS_JSON", points.toString(), "SUMMARY_JSON", directory.resolve("absent").toString()));
        process.environment().putAll(Map.of("PROCESSING_RABBITMQ_TEST_HOST", "127.0.0.1", "PROCESSING_RABBITMQ_TEST_PORT", "1",
                "PROCESSING_RABBITMQ_TEST_VHOST", "test", "PROCESSING_RABBITMQ_TEST_USERNAME", "test", "PROCESSING_RABBITMQ_TEST_PASSWORD", "test",
                "PROCESSING_RABBITMQ_TEST_MANAGEMENT_URL", "http://127.0.0.1:1", "PROCESSING_CLICKHOUSE_TEST_URL", "http://127.0.0.1:1",
                "PROCESSING_CLICKHOUSE_TEST_USERNAME", "test", "PROCESSING_CLICKHOUSE_TEST_PASSWORD", "test"));
        process.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile());
        Process child = process.start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertTrue(child.exitValue() != 0);
            var result = JSON.readTree(Files.readString(report));
            assertTrue(result.path("partial").asBoolean()); assertFalse(result.path("reconciled_counts").asBoolean());
            assertEquals(0, result.path("query_visibility_phases").path("measurement_gaps").asInt());
            assertEquals(0, result.path("observer").path("query_failed_attempts").asInt());
            assertTrue(result.path("query_visibility_phases").path("incomplete_evidence").asBoolean());
            assertTrue(result.path("query_visibility_phases").path("scenario_timestamp_missing").asBoolean());
            assertTrue(result.path("query_visibility_phases").path("measurement").path("mean_ms").isNull());
            assertEquals(1, result.path("accepted_manifest").asInt()); assertFalse(result.path("k6_summary_available").asBoolean());
        } finally { child.destroyForcibly(); }
    }
}
