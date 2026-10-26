package com.h.vanioak.modules.processing.internal;

import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit offline check only: no HTTP, RabbitMQ or load generation. */
public final class ProcessingObserverCapacityCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("New output directory required");
        Path directory = Path.of(args[0]); Files.createDirectory(directory);
        long started = System.nanoTime();
        var cpu = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = cpu.getProcessCpuTime();
        var scope = ProcessingObserverTest.SCOPE;
        var state = new ProcessingObserver.State();
        var failure = new AtomicReference<Throwable>();
        var acknowledgements = new AtomicInteger();
        Path journalPath = directory.resolve("events.tsv");
        try (var journal = new ProcessingObserver.Journal(FileChannel.open(journalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                scope, state, tag -> acknowledgements.incrementAndGet(), failure)) {
            for (int i = 319_999; i >= 0; i--) journal.append("realtime.queue", ProcessingObserverTest.event(i, "INFO", false), i);
            for (int i = 0; i < 1000; i++) journal.append("realtime.queue", ProcessingObserverTest.event(i, "INFO", false), 320_000L + i);
            journal.flush();
            Path rows = directory.resolve("stored.ndjson");
            try (var writer = Files.newBufferedWriter(rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                for (int i = 0; i < 320_000; i++) { writer.write(ProcessingObserverTest.row(i, "INFO")); writer.newLine(); }
                writer.write(ProcessingObserverTest.row(0, "INFO")); writer.newLine();
            }
            var stored = new HashMap<UUID, ProcessingObserver.Stored>();
            long streamStart = System.nanoTime();
            try (var input = Files.newInputStream(rows)) {
                ProcessingObserver.readRows(input, line -> ProcessingObserver.acceptStored(stored, ProcessingObserverTest.JSON.readTree(line), scope));
            }
            double streamSeconds = (System.nanoTime() - streamStart) / 1e9;
            ProcessingObserver.verifyJournal(journalPath, journal, stored, scope);
            Path points = directory.resolve("k6-points.json");
            try (var writer = Files.newBufferedWriter(points, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                for (int i = 0; i < 3200; i++) {
                    String prefix = ProcessingObserverTest.trace(i * 100).substring(0, 28);
                    writer.write("{\"type\":\"Point\",\"metric\":\"accepted_trace_batches\",\"data\":{\"value\":100,\"tags\":{\"trace_prefix\":\"" + prefix + "\"}}}\n");
                }
            }
            Path summary = directory.resolve("summary.json");
            Files.writeString(summary, "{\"configuration\":{\"run_id\":\"aabbccdd\"},\"measurements\":{\"accepted_logs\":320000}}", StandardOpenOption.CREATE_NEW);
            var report = ProcessingObserver.reconcile(stored, state, ProcessingObserver.acceptedTraces(points, scope.run()), true, summary, scope.run());
            if (!Boolean.TRUE.equals(report.get("reconciled_counts")) || !Boolean.TRUE.equals(report.get("accepted_to_stored_reconciled"))
                    || acknowledgements.get() != 321_000 || state.duplicates != 1000 || failure.get() != null)
                throw new IllegalStateException("Offline exact reconciliation failed");
            report.put("journal", journal.metrics()); report.put("acknowledgements", acknowledgements.get());
            report.put("wall_seconds", (System.nanoTime() - started) / 1e9);
            report.put("cpu_seconds", (cpu.getProcessCpuTime() - cpuStart) / 1e9);
            report.put("heap_max_bytes", Runtime.getRuntime().maxMemory());
            report.put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            report.put("stream_seconds", streamSeconds);
            try (var writer = Files.newBufferedWriter(directory.resolve("report.json"), StandardOpenOption.CREATE_NEW)) {
                ProcessingObserverTest.JSON.writeValue(writer, report);
            }
            System.out.println("PASS: 320000 exact identities, 1000 duplicate copies, streamed reconciliation, durable batched ACKs");
        }
    }
}
