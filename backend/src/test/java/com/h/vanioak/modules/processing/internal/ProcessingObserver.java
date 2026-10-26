package com.h.vanioak.modules.processing.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.rabbitmq.client.ConnectionFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Manually invoked observer only; never starts load or a Processing listener. */
public final class ProcessingObserver {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_IDENTITIES = 400_000;
    static final int MAX_COPIES = 1_000_000;
    static final long MAX_QUERY_MEMORY = 67_108_864;
    static final int JOURNAL_BATCH = 64;
    static final int MAX_EVENT_BYTES = 65_536;
    static final long MAX_JOURNAL_BYTES = 256L * 1024 * 1024;

    record Scope(String run, UUID application, UUID environment) {
        Scope {
            if (!run.matches("[0-9a-f]{8}") || application == null || environment == null)
                throw new IllegalArgumentException("Explicit valid run/application/environment scope required");
        }
        String filter() {
            return "application_id=toUUID('" + application + "') AND environment_id=toUUID('" + environment
                    + "') AND startsWith(trace_id, '" + run + "')";
        }
    }

    record Stored(String trace, String level) {
        boolean requiresCritical() { return level.equals("ERROR") || level.equals("CRITICAL"); }
    }
    record Event(UUID id, String trace, boolean critical, long tag) { }
    record Visibility(String event_id, String received_at, String observed_at, long latency_ms) { }
    @FunctionalInterface interface Ack { void send(long tag) throws IOException; }

    static final class State {
        final Set<UUID> processed = new HashSet<>();
        final Set<UUID> critical = new HashSet<>();
        long copies, duplicates;
        synchronized void record(Event event) {
            Set<UUID> route = event.critical ? critical : processed;
            if (!route.contains(event.id) && route.size() >= MAX_IDENTITIES)
                throw new IllegalStateException("Downstream identity bound exceeded");
            if (!route.add(event.id)) duplicates++;
            copies++;
        }
        synchronized int processedCount() { return processed.size(); }
        synchronized long duplicateCount() { return duplicates; }
    }

    /** One bounded batch; channel ACKs follow force, never buffered append. */
    static final class Journal implements AutoCloseable {
        private final FileChannel file;
        private final Scope scope;
        private final State state;
        private final Ack ack;
        private final List<Event> pending = new ArrayList<>(JOURNAL_BATCH);
        private final AtomicReference<Throwable> failure;
        private long copies, bytes;
        long forcedBytes, forceCount, forceNanos;
        boolean closed;

        Journal(FileChannel file, Scope scope, State state, Ack ack, AtomicReference<Throwable> failure) {
            this.file = file; this.scope = scope; this.state = state; this.ack = ack; this.failure = failure;
        }
        synchronized void append(String queue, byte[] body, long tag) throws IOException {
            if (closed || failure.get() != null) throw new IOException("Journal unavailable");
            if (body.length > MAX_EVENT_BYTES || copies >= MAX_COPIES)
                throw new IllegalStateException("Downstream copy/body bound exceeded");
            JsonNode event = JSON.readTree(body), payload = event.path("payload");
            String trace = payload.path("trace_id").asString();
            UUID id = UUID.fromString(event.path("event_id").asString());
            boolean critical = queue.equals("alert.queue");
            String level = payload.path("level").asString();
            if ((!queue.equals("realtime.queue") && !critical)
                    || !trace.matches(scope.run + "[0-9a-f]{24}")
                    || !id.toString().equals(payload.path("event_id").asString())
                    || !scope.application.toString().equals(payload.path("application_id").asString())
                    || !scope.environment.toString().equals(payload.path("environment_id").asString())
                    || !List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level)
                    || (critical && !List.of("ERROR", "CRITICAL").contains(level))
                    || !(critical ? "critical.log" : "processed.log").equals(event.path("event_type").asString()))
                throw new IllegalStateException("Unexpected downstream identity/scope/route");
            byte[] line = (scope.run + "\t" + queue + "\t" + id + "\t" + trace + "\t"
                    + scope.application + "\t" + scope.environment + "\t" + level + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes + line.length > MAX_JOURNAL_BYTES) throw new IllegalStateException("Journal byte bound exceeded");
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) file.write(buffer);
            bytes += line.length; copies++;
            pending.add(new Event(id, trace, critical, tag));
            if (pending.size() == JOURNAL_BATCH) flush();
        }
        synchronized void flush() throws IOException {
            if (closed || pending.isEmpty() || failure.get() != null) return;
            try {
                long start = System.nanoTime();
                file.force(true);
                forceNanos += System.nanoTime() - start; forceCount++; forcedBytes = bytes;
                for (Event event : pending) {
                    state.record(event);
                    ack.send(event.tag);
                }
                pending.clear();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
                if (ex instanceof IOException io) throw io;
                throw ex;
            }
        }
        synchronized Map<String, Object> metrics() {
            return Map.of("bytes_written", bytes, "durable_bytes", forcedBytes, "force_count", forceCount,
                    "force_ms", forceNanos / 1e6, "unacknowledged_batch_size", pending.size());
        }
        @Override public synchronized void close() throws IOException {
            try { flush(); } finally { closed = true; file.close(); }
        }
    }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("PROCESSING_TEST_SCOPE_APPROVED")))
            throw new IllegalStateException("Explicit isolated observer scope approval required");
        Scope scope = new Scope(required("RUN_ID"), UUID.fromString(required("PROCESSING_OBSERVER_APPLICATION_ID")),
                UUID.fromString(required("PROCESSING_OBSERVER_ENVIRONMENT_ID")));
        int seconds = Integer.parseInt(required("OBSERVE_SECONDS"));
        if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("Bound observation to 1..600 seconds");
        Path output = Path.of(required("PROCESSING_OBSERVER_OUTPUT"));
        if (Files.exists(output) || Files.exists(Path.of(output + ".ndjson")) || Files.exists(Path.of(output + ".events.tsv")))
            throw new IllegalArgumentException("Observer outputs must be new; preserve historical evidence");
        var factory = new ConnectionFactory();
        factory.setHost(required("PROCESSING_RABBITMQ_TEST_HOST"));
        factory.setPort(Integer.parseInt(required("PROCESSING_RABBITMQ_TEST_PORT")));
        factory.setVirtualHost(required("PROCESSING_RABBITMQ_TEST_VHOST"));
        factory.setUsername(required("PROCESSING_RABBITMQ_TEST_USERNAME"));
        factory.setPassword(required("PROCESSING_RABBITMQ_TEST_PASSWORD"));
        factory.setConnectionTimeout(5000);
        factory.setAutomaticRecoveryEnabled(false);
        URI clickhouse = URI.create(required("PROCESSING_CLICKHOUSE_TEST_URL"));
        String chAuth = authorization(required("PROCESSING_CLICKHOUSE_TEST_USERNAME"), required("PROCESSING_CLICKHOUSE_TEST_PASSWORD"));
        String rabbitAuth = authorization(factory.getUsername(), factory.getPassword());
        String management = required("PROCESSING_RABBITMQ_TEST_MANAGEMENT_URL").replaceAll("/+$", "");
        String vhost = URLEncoder.encode(factory.getVirtualHost(), StandardCharsets.UTF_8);
        State state = new State();
        var visibility = new HashMap<String, Visibility>();
        var stored = new HashMap<UUID, Stored>();
        var failure = new AtomicReference<Throwable>();
        var report = new HashMap<String, Object>();
        long start = System.nanoTime(), queryNanos = 0, cycles = 0;
        boolean finalReadComplete = false;
        long[] evidenceCounts = {0, 0, 0};
        Instant lastVisibilityEnd = null;
        Journal journal = null;
        ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
        try (var points = Files.newBufferedWriter(Path.of(output + ".ndjson"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            Consumer<Map<String, Object>> gaps = point -> recordEvidence(points, point, evidenceCounts);
            try (var connection = factory.newConnection("processing-test-observer"); var channel = connection.createChannel()) {
                for (String queue : List.of("realtime.queue", "alert.queue"))
                    if (channel.queueDeclarePassive(queue).getConsumerCount() != 0) throw new IllegalStateException("Unexpected downstream consumer");
                channel.basicQos(256);
                journal = new Journal(FileChannel.open(Path.of(output + ".events.tsv"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        scope, state, tag -> channel.basicAck(tag, false), failure);
                try (Journal activeJournal = journal) {
                    var flushTask = timer.scheduleWithFixedDelay(() -> {
                        try { activeJournal.flush(); } catch (Throwable ex) { failure.compareAndSet(null, ex); }
                    }, 100, 100, TimeUnit.MILLISECONDS);
                    try {
                        for (String queue : List.of("realtime.queue", "alert.queue"))
                            channel.basicConsume(queue, false, (tag, message) -> {
                                try { activeJournal.append(queue, message.getBody(), message.getEnvelope().getDeliveryTag()); }
                                catch (Throwable ex) { failure.compareAndSet(null, ex); }
                            }, tag -> failure.compareAndSet(null, new IllegalStateException("Observer consumer cancelled")));
                        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(seconds) && !Files.exists(Path.of(output + ".stop"))) {
                            long cycleStart = System.nanoTime();
                            if (failure.get() != null || !connection.isOpen()) throw new IllegalStateException("Observer transport/journal failure");
                            Instant aggregateStart = Instant.now();
                            var aggregate = new ArrayList<JsonNode>(1);
                            long aggregateNanos = System.nanoTime();
                            try { queryLines(client, timer, clickhouse, chAuth, aggregateQuery(scope), line -> aggregate.add(JSON.readTree(line)), gaps); }
                            finally { queryNanos += System.nanoTime() - aggregateNanos; }
                            aggregateNanos = System.nanoTime() - aggregateNanos;
                            Instant aggregateEnd = Instant.now();
                            if (aggregate.size() != 1) throw new IllegalStateException("Invalid aggregate result");
                            var sampleRows = new ArrayList<String>();
                            Instant sampleStart = Instant.now();
                            long sampleNanos = System.nanoTime();
                            try { queryLines(client, timer, clickhouse, chAuth, sampleQuery(scope), line -> {
                                if (sampleRows.size() >= 10_000) throw new IllegalStateException("Visibility sample bound exceeded");
                                sampleRows.add(line);
                            }, gaps); } finally { queryNanos += System.nanoTime() - sampleNanos; }
                            sampleNanos = System.nanoTime() - sampleNanos;
                            Instant sampleEnd = Instant.now();
                            List<Visibility> newlyVisible;
                            try { newlyVisible = recordVisibility(visibility, String.join("\n", sampleRows), sampleEnd); }
                            catch (RuntimeException ex) {
                                report.put("visibility_sample_error", "Missing/invalid timestamp or identity, changed received_at, clock ordering or sample bound violation");
                                throw ex;
                            }
                            lastVisibilityEnd = sampleEnd;
                            if (visibility.size() > 10_000) throw new IllegalStateException("Visibility identity bound exceeded");
                            var queues = new HashMap<String, JsonNode>();
                            for (String queue : List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue")) {
                                var request = HttpRequest.newBuilder(URI.create(management + "/api/queues/" + vhost + "/" + queue))
                                        .timeout(Duration.ofSeconds(5)).header("Authorization", rabbitAuth).GET().build();
                                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                                if (response.statusCode() != 200) throw new IOException("Management request unavailable");
                                JsonNode details = JSON.readTree(response.body());
                                var safe = JSON.createObjectNode();
                                for (String field : List.of("messages_ready", "messages_unacknowledged", "message_stats", "consumers")) safe.set(field, details.path(field));
                                queues.put(queue, safe);
                            }
                            var point = new HashMap<String, Object>();
                            point.put("at", Instant.now().toString()); point.put("elapsed_seconds", (System.nanoTime() - start) / 1e9);
                            point.put("persisted_unique", aggregate.getFirst().path("distinct_logs").asLong());
                            point.put("stored_physical_observed", aggregate.getFirst().path("physical_rows").asLong());
                            point.put("published_processed_unique", state.processedCount()); point.put("duplicate_event_copies", state.duplicateCount());
                            point.put("queues", queues); point.put("cycle_work_ms", (System.nanoTime() - cycleStart) / 1e6);
                            point.put("new_visibility_samples", newlyVisible);
                            point.put("new_visibility_latency_ms", newlyVisible.stream().map(Visibility::latency_ms).toList()); point.put("clickhouse_query_ms_total", queryNanos / 1e6);
                            point.put("aggregate_query_ms", aggregateNanos / 1e6); point.put("visibility_query_ms", sampleNanos / 1e6);
                            point.put("aggregate_started_at", aggregateStart.toString()); point.put("aggregate_finished_at", aggregateEnd.toString());
                            point.put("visibility_started_at", sampleStart.toString()); point.put("visibility_finished_at", sampleEnd.toString());
                            point.put("query_visibility_latency_ms", latencySummary(visibility.values().stream().map(Visibility::latency_ms).toList()));
                            point.put("journal", activeJournal.metrics());
                            gaps.accept(point); cycles++;
                            Thread.sleep(1000);
                        }
                        long finalQueryStart = System.nanoTime();
                        try { queryLines(client, timer, clickhouse, chAuth, finalQuery(scope), line -> acceptStored(stored, JSON.readTree(line), scope), point -> {
                            var finalPoint = new HashMap<>(point);
                            finalPoint.put("reconciliation_gap", point.get("measurement_gap"));
                            finalPoint.put("measurement_gap", false);
                            gaps.accept(finalPoint);
                        }); }
                        finally { queryNanos += System.nanoTime() - finalQueryStart; }

                    } finally { flushTask.cancel(false); }
                }
            }
            verifyJournal(Path.of(output + ".events.tsv"), journal, stored, scope);
            finalReadComplete = true;
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
        } finally {
            timer.shutdownNow();
            report.put("run_id", scope.run); report.put("final_read_complete", finalReadComplete);
            report.put("failure_type", failure.get() == null ? "none" : failure.get().getClass().getSimpleName());
            report.put("partial", failure.get() != null || !finalReadComplete);
            report.put("query_visibility_samples", visibility.values());
            report.put("query_visibility_latency_ms", latencySummary(visibility.values().stream().map(Visibility::latency_ms).toList()));
            report.put("observer", Map.of("cycles", cycles, "clickhouse_query_ms", queryNanos / 1e6, "sampling_sleep_ms", 1000,
                    "sample_fraction", .01, "query_memory_bytes", MAX_QUERY_MEMORY, "max_identities", MAX_IDENTITIES, "query_failed_attempts", evidenceCounts[1], "query_retries_scheduled", evidenceCounts[2]));
            report.put("journal", journal == null ? Map.of() : journal.metrics());
            var accepted = new HashSet<String>();
            try { acceptedTraces(Path.of(required("K6_POINTS_JSON")), scope.run, accepted); }
            catch (Throwable ex) { failure.compareAndSet(null, ex); report.put("accepted_manifest_partial", true); }
            try {
                report.putAll(reconcile(stored, state, accepted, finalReadComplete,
                        Path.of(required("SUMMARY_JSON")), scope.run));
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex); report.put("partial", true); report.put("reconciliation_failure_type", ex.getClass().getSimpleName());
                report.put("reconciled_counts", false); report.put("accepted_to_stored_reconciled", false);
            }
            if (failure.get() != null) {
                report.put("partial", true); report.put("failure_type", failure.get().getClass().getSimpleName());
                report.put("reconciled_counts", false); report.put("accepted_to_stored_reconciled", false);
            }
            Instant scenarioStart = null;
            String timingError = null;
            try { scenarioStart = scenarioStart(Path.of(required("K6_POINTS_JSON")), scope.run); }
            catch (Exception ex) { timingError = ex.getClass().getSimpleName(); }
            report.put("query_visibility_phases", visibilityPhases(visibility.values(), scenarioStart,
                    lastVisibilityEnd, evidenceCounts[0], Boolean.TRUE.equals(report.get("partial"))
                            || !Boolean.TRUE.equals(report.get("accepted_to_stored_reconciled")), timingError));
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) { JSON.writeValue(writer, report); }
        }
        if (failure.get() != null) throw new IllegalStateException("Observer failed; partial report and durable journal preserved", failure.get());
    }

    static void acceptStored(Map<UUID, Stored> stored, JsonNode row, Scope scope) {
        UUID id = UUID.fromString(row.path("event_id").asString());
        String trace = row.path("trace_id").asString(), level = row.path("level").asString();
        if (!trace.matches(scope.run + "[0-9a-f]{24}") || !List.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level))
            throw new IllegalStateException("Invalid stored scope/level");
        Stored value = new Stored(trace, level.intern());
        Stored previous = stored.putIfAbsent(id, value);
        if (previous == null && stored.size() > MAX_IDENTITIES) throw new IllegalStateException("Stored identity bound exceeded");
        if (previous != null && !previous.equals(value)) throw new IllegalStateException("Changed stored identity mapping");
    }

    static Map<String, Object> reconcile(Map<UUID, Stored> stored, State state, Set<String> accepted, boolean finalRead,
            Path summary, String run) throws IOException {
        int manifestCount = accepted.size();
        boolean summaryAvailable = Files.exists(summary);
        long acceptedCount = manifestCount;
        if (summaryAvailable) {
            if (Files.size(summary) > 1_048_576) throw new IOException("k6 summary byte bound exceeded");
            JsonNode root = JSON.readTree(Files.readString(summary));
            if (!run.equals(root.path("configuration").path("run_id").asString())) throw new IllegalStateException("k6 run differs");
            acceptedCount = root.path("measurements").path("accepted_logs").asLong();
        }
        var storedTraces = new HashSet<String>();
        var unexpected = new ArrayList<String>();
        var missingEvents = new ArrayList<UUID>();
        var notStored = new ArrayList<UUID>();
        long complete = 0;
        synchronized (state) {
            for (var entry : stored.entrySet()) {
                Stored row = entry.getValue();
                if (storedTraces.add(row.trace) && !accepted.remove(row.trace)) unexpected.add(row.trace);
                if (state.processed.contains(entry.getKey()) && (!row.requiresCritical() || state.critical.contains(entry.getKey()))) complete++;
                else missingEvents.add(entry.getKey());
            }
            for (UUID id : state.processed) if (!stored.containsKey(id)) notStored.add(id);
            for (UUID id : state.critical) if (!stored.containsKey(id) || !stored.get(id).requiresCritical()) notStored.add(id);
        }
        var report = new HashMap<String, Object>();
        report.put("accepted_logs", acceptedCount); report.put("accepted_manifest", manifestCount); report.put("k6_summary_available", summaryAvailable);
        report.put("accepted_count_source", summaryAvailable ? "k6_summary" : "exported_trace_points_lower_bound_after_interruption");
        report.put("persisted_unique", stored.size()); report.put("processed_unique", complete); report.put("duplicate_event_copies", state.duplicateCount());
        report.put("missing_accepted_traces", accepted); report.put("unexpected_stored_traces", unexpected);
        report.put("http_uncertain_stored_traces", summaryAvailable ? List.of() : unexpected);
        report.put("stored_ids_missing_required_events", missingEvents); report.put("published_ids_not_stored", notStored);
        report.put("reconciled_counts", finalRead && acceptedCount == stored.size() && accepted.isEmpty() && unexpected.isEmpty() && missingEvents.isEmpty() && notStored.isEmpty());
        report.put("accepted_to_stored_reconciled", finalRead && summaryAvailable && acceptedCount == manifestCount
                && stored.size() == storedTraces.size() && accepted.isEmpty() && unexpected.isEmpty());
        return report;
    }

    static Map<String, Object> latencySummary(java.util.Collection<Long> samples) {
        var sorted = new ArrayList<>(samples); sorted.sort(Long::compareTo);
        long sum = 0; for (long value : samples) sum = Math.addExact(sum, value);
        var result = new HashMap<String, Object>();
        result.put("sample_count", sorted.size()); result.put("sum_ms", sum);
        result.put("mean_ms", sorted.isEmpty() ? null : (double) sum / sorted.size());
        result.put("p50", percentile(sorted, .5)); result.put("p95", percentile(sorted, .95)); result.put("p99", percentile(sorted, .99));
        return result;
    }

    static String settings() {
        return " SETTINGS max_threads=1,max_memory_usage=" + MAX_QUERY_MEMORY
                + ",max_execution_time=5,max_result_rows=1000000,max_result_bytes=134217728,result_overflow_mode='throw' FORMAT JSONEachRow";
    }
    static String aggregateQuery(Scope scope) { return "SELECT uniqExact(event_id) AS distinct_logs, count() AS physical_rows FROM logs WHERE " + scope.filter() + settings(); }
    static String sampleQuery(Scope scope) { return "SELECT event_id, toString(received_at, 'UTC') AS received_at FROM logs WHERE " + scope.filter() + " AND cityHash64(trace_id) % 100 = 0" + settings(); }
    static String finalQuery(Scope scope) { return "SELECT event_id, trace_id, level FROM logs WHERE " + scope.filter() + settings(); }

    static void recordEvidence(BufferedWriter points, Map<String, Object> point, long[] counts) {
        try { points.write(JSON.writeValueAsString(point)); points.newLine(); points.flush(); }
        catch (IOException ex) { throw new IllegalStateException("Cannot persist observation evidence", ex); }
        if (Boolean.TRUE.equals(point.get("measurement_gap"))) counts[0]++;
        if (Boolean.TRUE.equals(point.get("query_attempt_failed"))) counts[1]++;
        if (Boolean.TRUE.equals(point.get("retry_scheduled"))) counts[2]++;
    }

    static int clickhouseCode(String header, String body) {
        if (header != null) {
            try { int code = Integer.parseInt(header.strip()); if (code > 0) return code; }
            catch (NumberFormatException ignored) { /* fall back to bounded response body */ }
        }
        String text = body.strip().replaceFirst("^\\uFEFF", "").strip();
        if (text.startsWith("{")) {
            try {
                JsonNode error = JSON.readTree(text);
                if (error.path("code").isIntegralNumber() && error.path("code").canConvertToInt()
                        && error.path("code").asInt() > 0) return error.path("code").asInt();
                text = error.path("exception").asString("").strip();
            } catch (RuntimeException ignored) { return -1; }
        }
        var match = Pattern.compile("^(?:DB::Exception:\\s*)?Code:\\s*([0-9]+)(?:[.,]|$)").matcher(text);
        if (match.find()) {
            try { int code = Integer.parseInt(match.group(1)); if (code > 0) return code; }
            catch (NumberFormatException ignored) { /* invalid numeric code is not retryable */ }
        }
        return -1;
    }

    static void queryLines(HttpClient client, ScheduledExecutorService timer, URI uri, String auth, String sql,
            Consumer<String> sink, Consumer<Map<String, Object>> gaps) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String observation = UUID.randomUUID().toString();
        Instant queryStart = Instant.now();
        boolean gapRecorded = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long remaining = deadline - System.nanoTime();
            boolean recorded = false;
            int status = -1, code = -1;
            try {
                if (remaining <= 0) throw new IOException("ClickHouse retry deadline exceeded");
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).header("Authorization", auth).POST(HttpRequest.BodyPublishers.ofString(sql)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                status = response.statusCode();
                try (InputStream body = response.body()) {
                    var expired = new java.util.concurrent.atomic.AtomicBoolean();
                    long bodyBudget = Math.max(1, deadline - System.nanoTime());
                    var closeTask = timer.schedule(() -> { expired.set(true); try { body.close(); } catch (IOException ignored) { /* original read failure is reported */ } }, bodyBudget, TimeUnit.NANOSECONDS);
                    try {
                        String header = response.headers().firstValue("X-ClickHouse-Exception-Code").orElse(null);
                        code = clickhouseCode(header, "");
                        if (status != 200 || code > 0) {
                            code = clickhouseCode(header, new String(body.readNBytes(8192), StandardCharsets.UTF_8));
                            boolean retry = code == 241 && (status == 500 || status == 200) && attempt < 3
                                    && !expired.get() && deadline - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(250L * attempt);
                            gaps.accept(Map.of("at", Instant.now().toString(), "query_started_at", queryStart.toString(),
                                    "query_observation_id", observation, "measurement_gap", !gapRecorded,
                                    "query_attempt_failed", true, "attempt", attempt, "http_status", status,
                                    "clickhouse_code", code, "retry_scheduled", retry));
                            recorded = true; gapRecorded = true;
                            if (!retry) throw new IOException("ClickHouse query unavailable; sanitized evidence recorded");
                            Thread.sleep(250L * attempt);
                            continue;
                        }
                        readRows(body, sink);
                        if (expired.get()) throw new IOException("ClickHouse stream deadline exceeded");
                        return;
                    } finally { closeTask.cancel(false); }
                }
            } catch (Exception ex) {
                if (!recorded) gaps.accept(Map.of("at", Instant.now().toString(), "query_started_at", queryStart.toString(),
                        "query_observation_id", observation, "measurement_gap", !gapRecorded,
                        "query_attempt_failed", true, "attempt", attempt, "http_status", status,
                        "clickhouse_code", code, "retry_scheduled", false, "failure_type", ex.getClass().getSimpleName()));
                if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); throw ex; }
                throw new IOException("ClickHouse observation failed; sanitized evidence recorded");
            }
        }
    }

    static List<Visibility> recordVisibility(Map<String, Visibility> first, String rows, Instant observed) {
        var newlyVisible = new ArrayList<Visibility>();
        for (String line : rows.lines().toList()) {
            JsonNode row = JSON.readTree(line);
            String id = row.path("event_id").asString();
            Instant received = Instant.parse(row.path("received_at").asString().replace(' ', 'T') + "Z");
            if (id == null || id.isBlank() || received.isAfter(observed))
                throw new IllegalStateException("Invalid visibility identity or clock ordering");
            Visibility previous = first.get(id);
            if (previous != null) {
                if (!previous.received_at.equals(received.toString()))
                    throw new IllegalStateException("Changed sampled received_at");
                continue;
            }
            if (first.size() >= 10_000) throw new IllegalStateException("Visibility identity bound exceeded");
            var sample = new Visibility(id, received.toString(), observed.toString(), Duration.between(received, observed).toMillis());
            first.put(id, sample);
            newlyVisible.add(sample);
        }
        return newlyVisible;
    }

    static Instant scenarioStart(Path points, String run) throws IOException {
        Instant[] start = {null};
        try (var input = Files.newInputStream(points)) {
            readRows(input, line -> {
                JsonNode point = JSON.readTree(line);
                if (!"Point".equals(point.path("type").asString()) || !"request_timing_ms".equals(point.path("metric").asString())) return;
                JsonNode tags = point.path("data").path("tags");
                if (!tags.path("trace_prefix").asString().matches(run + "[0-9a-f]{20}"))
                    throw new IllegalStateException("Invalid timing point run scope");
                long millis = Long.parseLong(tags.path("scenario_start_ms").asString());
                if (millis <= 0) throw new IllegalStateException("Invalid scenario start timestamp");
                Instant value = Instant.ofEpochMilli(millis);
                if (start[0] != null && !start[0].equals(value))
                    throw new IllegalStateException("Conflicting scenario start timestamps");
                start[0] = value;
            }, false);
        }
        return start[0];
    }

    static Map<String, Object> visibilityPhases(java.util.Collection<Visibility> samples, Instant start,
            Instant lastObservation, long gaps, boolean partial, String timingError) {
        var result = new HashMap<String, Object>();
        var warmup = new ArrayList<Long>();
        var measurement = new ArrayList<Long>();
        long outside = 0;
        if (start != null) {
            Instant boundary = start.plusSeconds(20), end = start.plusSeconds(320);
            for (Visibility sample : samples) {
                Instant received = Instant.parse(sample.received_at);
                if (received.isBefore(start) || !received.isBefore(end)) outside++;
                else if (received.isBefore(boundary)) warmup.add(sample.latency_ms);
                else measurement.add(sample.latency_ms);
            }
            result.put("warmup_start", start.toString()); result.put("measurement_start", boundary.toString());
            result.put("measurement_end", end.toString());
        }
        result.put("warmup", latencySummary(warmup)); result.put("measurement", latencySummary(measurement));
        result.put("classification", "received_at; half-open 20s warmup and 300s measurement; includes drain observations");
        result.put("scenario_start_source", "k6 request_timing_ms scenario_start_ms; no inferred fallback");
        result.put("scenario_timestamp_missing", start == null);
        result.put("timing_evidence_error", timingError);
        result.put("measurement_gaps", gaps); result.put("outside_phase_samples", outside);
        result.put("incomplete_evidence", partial || gaps > 0 || timingError != null || start == null
                || lastObservation == null || lastObservation.isBefore(start.plusSeconds(320)));
        result.put("warmup_incomplete_evidence", partial || gaps > 0 || timingError != null || start == null
                || lastObservation == null || lastObservation.isBefore(start.plusSeconds(20)));
        result.put("sample_population_completeness", "NOT_CONFIRMED: unobserved sampled IDs are not enumerated");
        result.put("clock_alignment", "UNVERIFIED: k6 host, backend received_at and observer clocks must align; millisecond precision and polling/query delay apply");
        return result;
    }

	static Set<String> acceptedTraces(Path points, String run) throws Exception {
		var traces = new HashSet<String>();
        acceptedTraces(points, run, traces);
        return traces;
    }

    static void acceptedTraces(Path points, String run, Set<String> traces) throws IOException {
		try (var input = Files.newInputStream(points)) {
			readRows(input, line -> {
				JsonNode point = JSON.readTree(line);
				if (!"Point".equals(point.path("type").asString()) || !"accepted_trace_batches".equals(point.path("metric").asString())) return;
				String prefix = point.path("data").path("tags").path("trace_prefix").asString();
				if (!prefix.matches(run + "[0-9a-f]{20}")) throw new IllegalStateException("Invalid accepted trace prefix");
				int count = point.path("data").path("value").asInt();
				if (count < 1 || count > 1000) throw new IllegalStateException("Invalid accepted batch size");
				if (traces.size() + count > MAX_IDENTITIES) throw new IllegalStateException("Accepted identity bound exceeded");
				for (int i = 0; i < count; i++) if (!traces.add(prefix + java.util.HexFormat.of().toHexDigits((short) i))) throw new IllegalStateException("Duplicate accepted input trace");
			}, false);
		}
	}


    static void readRows(InputStream body, Consumer<String> sink) throws IOException {
        readRows(body, sink, true);
    }

    private static void readRows(InputStream body, Consumer<String> sink, boolean requireNewline) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        var line = new StringBuilder(256);
        int value, rows = 0;
        while ((value = reader.read()) != -1) {
            if (value == '\n') {
                if (++rows > MAX_COPIES) throw new IOException("ClickHouse result row bound exceeded");
                if (!line.isEmpty()) sink.accept(line.toString());
                line.setLength(0);
            } else {
                if (line.length() >= MAX_EVENT_BYTES) throw new IOException("ClickHouse result line bound exceeded");
                line.append((char) value);
            }
        }
        if (!line.isEmpty()) {
            if (requireNewline) throw new IOException("Truncated ClickHouse JSONEachRow result");
            if (++rows > MAX_COPIES) throw new IOException("Input row bound exceeded");
            sink.accept(line.toString());
        }
    }

    static void verifyJournal(Path path, Journal journal, Map<UUID, Stored> stored, Scope scope) throws IOException {
        synchronized (journal) {
            journal.flush();
            long consumed = 0;
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consumed += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (consumed > journal.forcedBytes) break;
                    String[] fields = line.split("\\t", -1);
                    if (fields.length != 7 || !fields[0].equals(scope.run)
                            || !fields[4].equals(scope.application.toString()) || !fields[5].equals(scope.environment.toString()))
                        throw new IOException("Invalid durable journal scope");
                    Stored row = stored.get(UUID.fromString(fields[2]));
                    if (row == null || !row.trace.equals(fields[3]) || !row.level.equals(fields[6]))
                        throw new IOException("Durable event differs from stored identity");
                }
            }
        }
    }

    private static long percentile(List<Long> values, double quantile) {
        return values.isEmpty() ? -1 : values.get((int) Math.ceil(values.size() * quantile) - 1);
    }
    private static String authorization(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Explicit observer setting required: " + key);
        return value;
    }
}
