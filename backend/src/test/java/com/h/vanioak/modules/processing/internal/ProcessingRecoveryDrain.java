package com.h.vanioak.modules.processing.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;
import com.h.vanioak.common.config.clickhouse.ClickHouseProperties;
import com.h.vanioak.modules.processing.internal.config.ProcessingConsumerConfiguration;
import com.h.vanioak.modules.processing.internal.consumer.RawLogConsumer;
import com.h.vanioak.modules.processing.internal.normalization.RawLogNormalizer;
import com.h.vanioak.modules.processing.internal.persistence.ClickHouseLogWriter;
import com.h.vanioak.modules.processing.internal.persistence.ProcessingBatchWriter;
import com.h.vanioak.modules.processing.internal.publisher.ProcessedLogPublisher;
import com.h.vanioak.modules.processing.internal.publisher.ProcessingFailurePublisher;
import com.rabbitmq.client.ConnectionFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One manually authorized recovery, never a load generator or ordinary Maven test. */
public final class ProcessingRecoveryDrain {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RUN = "3c18a08e";
    private static final String APP = "ca1ef773-b0cc-4e9c-826c-5ebc51dc7ca1";
    private static final String ENV = "ced242d1-dc03-48b9-9520-c9f1ad2db489";
    private static final List<String> QUEUES = List.of("raw.queue", "raw.retry.queue", "dead-letter.queue", "realtime.queue", "alert.queue");
    private static final Map<String, List<Long>> TIMES = new TreeMap<>();
    private static Path output;
    private static String auth;
    private static long timingEvidenceNanos;

    // shortcut: synchronous evidence writes distort timings; report their measured cost, not capacity.
    public static synchronized void record(String stage, long nanos, int rows) {
        long evidenceStart = System.nanoTime();
        TIMES.computeIfAbsent(stage, key -> new ArrayList<>()).add(nanos);
        try { Files.writeString(output.resolve("stage-timings.ndjson"), JSON.writeValueAsString(Map.of("stage", stage,
                "duration_ns", nanos, "rows", rows, "at", Instant.now().toString())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception failure) { throw new IllegalStateException("Timing evidence unavailable"); }
        finally { timingEvidenceNanos += System.nanoTime()-evidenceStart; }
    }

    static Map<String, Object> summary(List<Long> values) {
        var sorted = new ArrayList<>(values); sorted.sort(Long::compareTo);
        return Map.of("count", sorted.size(), "total_ms", sorted.stream().mapToDouble(x -> x / 1e6).sum(),
                "p50_ms", percentile(sorted, .50), "p95_ms", percentile(sorted, .95), "p99_ms", percentile(sorted, .99));
    }
    private static double percentile(List<Long> sorted, double q) { return sorted.isEmpty() ? -1 : sorted.get((int)Math.ceil(q * sorted.size()) - 1) / 1e6; }
    private static String setting(String key) { String value = System.getenv(key); if(value == null || value.isBlank()) throw new IllegalStateException("Missing isolated setting: " + key); return value; }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static String http(String url, String sql) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization", auth);
            var request = sql == null ? builder.GET().build() : builder.POST(HttpRequest.BodyPublishers.ofString(sql)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == 200, "Isolated HTTP request failed; body omitted"); return response.body();
        }
    }
    private static JsonNode queues() throws Exception { return JSON.readTree(http("http://127.0.0.1:25673/api/queues/processing-test", null)); }
    private static Map<String, JsonNode> stored() throws Exception {
        var result = new HashMap<String, JsonNode>();
        String sql = "SELECT event_id, trace_id, application_id, environment_id, level, received_at FROM logs WHERE startsWith(trace_id, '" + RUN + "') FORMAT JSONEachRow";
        for (String line : http("http://127.0.0.1:28123", sql).lines().toList()) { var row = JSON.readTree(line); own(row); result.put(row.path("event_id").asString(), row); }
        return result;
    }
    private static void own(JsonNode log) {
        require(log.path("trace_id").asString().matches(RUN + "[0-9a-f]{24}"), "Unexpected message trace ownership");
        require(APP.equals(log.path("application_id").asString()) && ENV.equals(log.path("environment_id").asString()), "Unexpected authenticated scope");
        require("INFO".equals(log.path("level").asString()), "Unexpected workload severity");
        UUID.fromString(log.path("event_id").asString());
    }
    private static String hash(byte[] body) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
    private static ConnectionFactory nativeFactory() {
        var factory = new ConnectionFactory(); factory.setHost("127.0.0.1"); factory.setPort(25672); factory.setVirtualHost("processing-test");
        factory.setUsername(setting("PROCESSING_RABBITMQ_TEST_USERNAME")); factory.setPassword(setting("PROCESSING_RABBITMQ_TEST_PASSWORD"));
        factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(5000); return factory;
    }
    private static void write(String name, Object value) throws Exception { Files.writeString(output.resolve(name), JSON.writeValueAsString(value)); }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "self-check".equals(args[0])) {
            require(((Number)summary(List.of(1_000_000L, 3_000_000L)).get("p50_ms")).doubleValue() == 1, "Timing percentile failed");
            try { own(JSON.readTree("{}")); throw new AssertionError("Ownership guard failed"); } catch (IllegalStateException expected) { }
            System.out.println("Recovery timing/ownership self-check passed; no infrastructure used"); return;
        }
        require("true".equals(setting("PROCESSING_DRAIN_APPROVED")), "Explicit single recovery authorization required");
        require("127.0.0.1".equals(setting("PROCESSING_RABBITMQ_TEST_HOST")) && "25672".equals(setting("PROCESSING_RABBITMQ_TEST_PORT"))
                && "processing-test".equals(setting("PROCESSING_RABBITMQ_TEST_VHOST")) && "http://127.0.0.1:28123".equals(setting("PROCESSING_CLICKHOUSE_TEST_URL")), "Unexpected isolated endpoint");
        output = Path.of(setting("PROCESSING_DRAIN_OUTPUT")); Files.createDirectories(output);
        auth = "Basic " + Base64.getEncoder().encodeToString((setting("PROCESSING_CLICKHOUSE_TEST_USERNAME") + ":" + setting("PROCESSING_CLICKHOUSE_TEST_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        var factory = nativeFactory();
        JsonNode before = queues(); write("queues-before.json", before);
        require(before.size() == 5, "Unexpected queue set");
        for (JsonNode q : before) require(q.path("consumers").asInt() == 0 && q.path("messages_unacknowledged").asInt() == 0, "Fixture is not idle");
        var rawIds = new HashMap<String, String>(); var rawHashes = new HashSet<String>(); var downstreamIds = new HashSet<String>();
        var normalizer = new RawLogNormalizer(JSON);
        try (var connection = factory.newConnection("processing-recovery-inventory"); var channel = connection.createChannel()) {
            // Hold deliveries during complete inventory. Closing this channel requeues all, without any raw ACK.
            for (String queue : QUEUES) {
                int count = channel.queueDeclarePassive(queue).getMessageCount();
                require(count <= 1000, "Unexpected inventory size");
                if (!queue.equals("raw.queue") && !queue.equals("realtime.queue")) require(count == 0, "Unexpected retry/DLQ/critical work");
                for (int i = 0; i < count; i++) {
                    var message = channel.basicGet(queue, false); require(message != null, "Inventory changed");
                    if (queue.equals("raw.queue")) {
                        var logs = normalizer.normalize(message.getBody()); require(logs.size() == 100, "Unexpected raw batch size");
                        for (var log : logs) {
                            own(JSON.readTree(JSON.writeValueAsString(Map.of("event_id", log.eventId().toString(), "trace_id", log.traceId(),
                                    "application_id", log.applicationId().toString(), "environment_id", log.environmentId().toString(), "level", log.level()))));
                            require(rawIds.put(log.eventId().toString(), log.traceId()) == null, "Unexpected duplicate pending raw ID");
                        }
                        require(rawHashes.add(hash(message.getBody())), "Unexpected duplicate raw body");
                    } else {
                        var event = JSON.readTree(message.getBody()); var payload = event.path("payload"); own(payload);
                        require("processed.log".equals(event.path("event_type").asString()) && event.path("event_id").asString().equals(payload.path("event_id").asString()), "Unexpected downstream contract");
                        require(downstreamIds.add(payload.path("event_id").asString()), "Unexpected duplicate initial downstream copy");
                    }
                }
            }
        }
        write("pending-raw-ids.json", rawIds); write("pending-raw-hashes.json", rawHashes); write("pending-downstream-ids.json", downstreamIds);
        var initialStored = stored(); write("stored-before.json", initialStored);
        var previouslyCompleted = new HashSet<>(initialStored.keySet()); previouslyCompleted.removeAll(rawIds.keySet());
        var previouslyObserved = new HashSet<>(previouslyCompleted); previouslyObserved.removeAll(downstreamIds);
        var priorPublished = new HashSet<>(previouslyCompleted); priorPublished.addAll(downstreamIds);
        long oldPublished = 0, oldAcked = 0, rawAcked = 0;
        for (JsonNode q : before) {
            if ("realtime.queue".equals(q.path("name").asString())) { oldPublished = q.path("message_stats").path("publish").asLong(); oldAcked = q.path("message_stats").path("ack").asLong(); }
            if ("raw.queue".equals(q.path("name").asString())) rawAcked = q.path("message_stats").path("ack").asLong();
        }
        require(rawIds.size() == 5900 && downstreamIds.size() == 241 && initialStored.size() == 24700, "Fixture differs from approved recovery evidence");
        require(previouslyCompleted.size() == rawAcked * 100 && priorPublished.size() == oldPublished && previouslyObserved.size() == oldAcked, "Previous publication accounting cannot be reconstructed");
        require(initialStored.keySet().containsAll(downstreamIds), "Published IDs missing initial persistence");
        var accepted = ProcessingObserver.acceptedTraces(Path.of(setting("K6_POINTS_JSON")), RUN);
        require(accepted.size() == 29000, "Accepted trace evidence differs");
        write("previously-observed-ids-reconstructed.json", previouslyObserved);
        write("inventory.json", Map.of("raw_messages", rawHashes.size(), "raw_logs", rawIds.size(), "downstream_pending", downstreamIds.size(),
                "previously_completed_raw_log_ids", previouslyCompleted.size(), "previously_observed_reconstructed_ids", previouslyObserved.size(), "prior_published_ids", priorPublished.size(), "accepted_traces", accepted.size()));
        System.out.println("Ownership/accounting verified: pending raw=" + rawHashes.size() + ", pending downstream=" + downstreamIds.size() + ", previous observed IDs reconstructed=" + previouslyObserved.size());
        if (args.length == 1 && "inventory".equals(args[0])) return;
        require(args.length == 1 && "drain".equals(args[0]), "Expected inventory or drain mode");
        var observed = ConcurrentHashMap.<String>newKeySet(); var errors = new AtomicReference<String>();
        var spring = new CachingConnectionFactory("127.0.0.1", 25672); spring.setVirtualHost("processing-test");
        spring.setUsername(setting("PROCESSING_RABBITMQ_TEST_USERNAME")); spring.setPassword(setting("PROCESSING_RABBITMQ_TEST_PASSWORD"));
        spring.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); spring.setPublisherReturns(true);
        var rabbit = new RabbitTemplate(spring); rabbit.setMandatory(true);
        var clickhouseConnection = new ClickHouseConnection(new ClickHouseProperties(URI.create("http://127.0.0.1:28123"), setting("PROCESSING_CLICKHOUSE_TEST_USERNAME"), setting("PROCESSING_CLICKHOUSE_TEST_PASSWORD"), Duration.ofSeconds(5), Duration.ofSeconds(5)));
        var writer = new ProcessingBatchWriter(new ClickHouseLogWriter(clickhouseConnection, JSON), 1000, 2000);
        var consumer = new RawLogConsumer(normalizer, writer, new ProcessedLogPublisher(rabbit, JSON, Duration.ofSeconds(5)),
                new ProcessingFailurePublisher(rabbit, JSON, Duration.ofSeconds(5), 3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120))), 16);
        var method = RawLogConsumer.class.getMethod("consume", Message.class);
        var adapter = new MessagingMessageListenerAdapter(consumer, method);
        adapter.setHandlerAdapter(new HandlerAdapter(new InvocableHandlerMethod(consumer, method)));
        var listenerFactory = (SimpleRabbitListenerContainerFactory)ReflectionTestUtils.invokeMethod(new ProcessingConsumerConfiguration(), "processingRawListenerFactory", spring, 16);
        adapter.setMessageConverter((org.springframework.amqp.support.converter.MessageConverter)ReflectionTestUtils.getField(listenerFactory, "messageConverter"));
        adapter.containerAckMode(AcknowledgeMode.MANUAL); adapter.setDefaultRequeueRejected(true);
        var endpoint = new SimpleRabbitListenerEndpoint(); endpoint.setId("approvedRecoveryDrain"); endpoint.setQueueNames("raw.queue");
        endpoint.setMessageListener((ChannelAwareMessageListener)(message, channel) -> {
            if (!rawHashes.contains(hash(message.getBody()))) { errors.set("Unexpected raw body"); channel.basicReject(message.getMessageProperties().getDeliveryTag(), true); return; }
            adapter.onMessage(message, channel);
        });
        var container = listenerFactory.createListenerContainer(endpoint);
        var logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(MessagingMessageListenerAdapter.class); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        long start = System.nanoTime(); String outcome = "deadline"; long copies = 0;
        try (var connection = factory.newConnection("processing-recovery-observer"); var channel = connection.createChannel()) {
            container.start();
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(170)) {
                require(errors.get() == null, "Unexpected message; stopping recovery");
                for (String queue : List.of("realtime.queue", "alert.queue")) {
                    for (int i = 0; i < 500; i++) {
                        var delivery = channel.basicGet(queue, false); if(delivery == null) break;
                        var event = JSON.readTree(delivery.getBody()); var payload = event.path("payload");
                        try {
                            own(payload); String id = payload.path("event_id").asString();
                            require("realtime.queue".equals(queue) && "processed.log".equals(event.path("event_type").asString()) && id.equals(event.path("event_id").asString()), "Unexpected downstream event");
                            require(priorPublished.contains(id) || rawIds.containsKey(id), "Unexpected downstream event ID");
                            // Flush evidence before ACK so interruption does not erase the new identity journal.
                            Files.writeString(output.resolve("observed-events.ndjson"), JSON.writeValueAsString(Map.of("event_id", id, "trace_id", payload.path("trace_id").asString(), "at", Instant.now().toString())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            observed.add(id); copies++; channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        } catch (Exception failure) { channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true); throw failure; }
                    }
                }
                var state = queues();
                Files.writeString(output.resolve("queue-series.ndjson"), JSON.writeValueAsString(Map.of("at", Instant.now().toString(), "elapsed_seconds", (System.nanoTime()-start)/1e9, "queues", state)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                boolean empty = true;
                for (JsonNode q : state) {
                    int pending = q.path("messages_ready").asInt() + q.path("messages_unacknowledged").asInt();
                    if (q.path("name").asString().equals("raw.retry.queue") || q.path("name").asString().equals("dead-letter.queue")) require(pending == 0, "Failure activity; stopping recovery");
                    empty &= pending == 0;
                }
                if (empty && observed.containsAll(rawIds.keySet()) && observed.containsAll(downstreamIds)) { outcome="drained"; break; }
                Thread.sleep(250);
            }
        } finally {
            container.stop(); consumer.close(); writer.close(); clickhouseConnection.close(); spring.destroy();
            write("stage-summary.json", TIMES.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> summary(e.getValue()))));
            write("queues-after.json", queues());
            write("instrumentation-overhead.json", Map.of("recording_total_ms", timingEvidenceNanos/1e6));
        }
        var finalStored = stored(); write("stored-after.json", finalStored);
        var fullObserved = new HashSet<>(previouslyObserved); fullObserved.addAll(observed);
        var expectedIds = new HashSet<>(previouslyCompleted); expectedIds.addAll(rawIds.keySet());
        var storedTraces = new HashSet<String>(); finalStored.values().forEach(row -> storedTraces.add(row.path("trace_id").asString()));
        var missingAccepted = new HashSet<>(accepted); missingAccepted.removeAll(storedTraces);
        var uncertain = new HashSet<>(storedTraces); uncertain.removeAll(accepted);
        var missingEvents = new HashSet<>(expectedIds); missingEvents.removeAll(fullObserved);
        write("recovery-result.json", Map.of("outcome", outcome, "duration_seconds", (System.nanoTime()-start)/1e9,
                "stored_distinct", finalStored.size(), "accepted_missing", missingAccepted, "uncertain_http_traces_stored", uncertain,
                "new_observed_distinct", observed.size(), "new_observed_copies", copies, "previous_reconstructed_observed", previouslyObserved.size(),
                "combined_observed_distinct", fullObserved.size(), "missing_required_events", missingEvents));
        System.out.println("Recovery outcome=" + outcome + "; stored=" + finalStored.size() + "; combined downstream=" + fullObserved.size());
    }
}
