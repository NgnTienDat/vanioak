package com.h.vanioak.modules.retention.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.h.vanioak.common.config.clickhouse.ClickHouseConnection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

class RetentionTtlMonitorTest {
	private static final String PREFIX = "CREATE TABLE default.logs (`event_id` UUID, `timestamp` DateTime64(3, 'UTC')) "
			+ "ENGINE = ReplacingMergeTree(timestamp) ORDER BY timestamp ";
	private final ObjectMapper mapper = new ObjectMapper();
	private final ClickHouseConnection clickHouse = mock(ClickHouseConnection.class);
	private final RetentionTtlMonitor monitor = new RetentionTtlMonitor(clickHouse, mapper);

	@Test
	void acceptsInstalledContractAndCanonicalSyntax() throws Exception {
		for (String ttl : new String[] {"timestamp + INTERVAL 7 DAY DELETE", "`timestamp` + toIntervalDay(7)"}) {
			when(clickHouse.queryReadOnly(anyString())).thenReturn(metadata(PREFIX + "TTL " + ttl + " SETTINGS index_granularity=8192"));
			assertEquals(RetentionTtlMonitor.Status.OK, monitor.probe().status());
			assertEquals("POLICY_MATCH", monitor.latest().reason());
		}
		verify(clickHouse, times(2)).queryReadOnly(argThat(sql -> sql.startsWith("SELECT create_table_query FROM system.tables")
				&& sql.contains("database = currentDatabase()") && sql.contains("name = 'logs' LIMIT 1")
				&& !sql.contains("FROM logs")));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"TTL timestamp + INTERVAL 8 DAY DELETE",
			"TTL received_at + INTERVAL 7 DAY DELETE",
			"TTL timestamp + INTERVAL 7 DAY DELETE WHERE level = 'ERROR'",
			"TTL timestamp + INTERVAL 7 DAY DELETE, timestamp + INTERVAL 8 DAY MOVE TO VOLUME 'cold'",
			"TTL timestamp + INTERVAL 7 DAY GROUP BY level",
			"TTL timestamp + INTERVAL 7 DAY RECOMPRESS CODEC(ZSTD(3))",
			"ORDER BY timestamp"
	})
	void rejectsDefinitePolicyMismatch(String tail) {
		assertEquals(RetentionTtlMonitor.Status.MISMATCH,
				RetentionTtlMonitor.classify(PREFIX + tail + " SETTINGS index_granularity=8192"));
	}

	@Test
	void columnLevelTtlNeverCountsAsTableLevel() {
		String ddl = "CREATE TABLE logs (`timestamp` DateTime64(3, 'UTC'), `level` String TTL timestamp + INTERVAL 7 DAY DELETE) "
				+ "ENGINE = MergeTree ORDER BY timestamp";
		assertEquals(RetentionTtlMonitor.Status.MISMATCH, RetentionTtlMonitor.classify(ddl));
		assertEquals(RetentionTtlMonitor.Status.MISMATCH,
				RetentionTtlMonitor.classify(ddl + " TTL timestamp + INTERVAL 7 DAY DELETE"));
	}

	@Test
	void missingOrAmbiguousMetadataIsUnknown() throws Exception {
		for (String response : new String[] {"", "not json", "{}", "{\"create_table_query\":4}",
				metadata(PREFIX + "TTL timestamp + INTERVAL 7 DAY DELETE") + metadata(PREFIX),
				metadata("malformed TTL timestamp + INTERVAL 7 DAY DELETE")}) {
			when(clickHouse.queryReadOnly(anyString())).thenReturn(response);
			assertEquals(RetentionTtlMonitor.Status.UNKNOWN, monitor.probe().status());
		}
		when(clickHouse.queryReadOnly(anyString())).thenReturn("not json");
		assertEquals("INVALID_METADATA", monitor.probe().reason());
		assertEquals(RetentionTtlMonitor.Status.UNKNOWN,
				RetentionTtlMonitor.classify(PREFIX + "TTL timestamp + toIntervalHour(168) DELETE"));
		assertEquals(RetentionTtlMonitor.Status.UNKNOWN,
				RetentionTtlMonitor.classify(PREFIX + "TTL timestamp + INTERVAL 7 DAY DELETE /*comment*/"));
	}

	@Test
	void queryFailureIsUnknownAndLogsOnlySafeReason() throws Exception {
		when(clickHouse.queryReadOnly(anyString())).thenThrow(new IOException("secret ddl and credential"));
		var logger = (Logger) org.slf4j.LoggerFactory.getLogger(RetentionTtlMonitor.class);
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			monitor.scheduled();
			assertEquals(RetentionTtlMonitor.Status.UNKNOWN, monitor.latest().status());
			assertEquals("QUERY_UNAVAILABLE", monitor.latest().reason());
			assertEquals(1, appender.list.size());
			String line = appender.list.getFirst().getFormattedMessage();
			assertTrue(line.contains("status=UNKNOWN reason=QUERY_UNAVAILABLE checked_at="));
			assertFalse(line.contains("secret"));
			assertFalse(line.contains("SELECT"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void schedulingHasApprovedCadenceAndSingleRegistration() throws Exception {
		assertTrue(RetentionSchedulingConfiguration.class.isAnnotationPresent(EnableScheduling.class));
		Scheduled schedule = RetentionTtlMonitor.class.getDeclaredMethod("scheduled").getAnnotation(Scheduled.class);
		assertNotNull(schedule);
		assertEquals(60_000, schedule.initialDelay());
		assertEquals(3_600_000, schedule.fixedDelay());
		try (var context = new AnnotationConfigApplicationContext()) {
			context.register(RetentionSchedulingConfiguration.class, RetentionTtlMonitor.class);
			context.registerBean(ClickHouseConnection.class, () -> clickHouse);
			context.registerBean(ObjectMapper.class, () -> mapper);
			context.refresh();
			assertEquals(1, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
		}
	}

	@Test
	void concurrentProbesCannotOverlap() throws Exception {
		var firstEntered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var secondStarted = new CountDownLatch(1);
		when(clickHouse.queryReadOnly(anyString())).thenAnswer(invocation -> {
			firstEntered.countDown();
			assertTrue(release.await(5, TimeUnit.SECONDS));
			return metadata(PREFIX + "TTL timestamp + INTERVAL 7 DAY DELETE");
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(monitor::probe);
			assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
			var second = executor.submit(() -> { secondStarted.countDown(); return monitor.probe(); });
			assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
			verify(clickHouse, times(1)).queryReadOnly(anyString());
			release.countDown();
			assertEquals(RetentionTtlMonitor.Status.OK, first.get(5, TimeUnit.SECONDS).status());
			assertEquals(RetentionTtlMonitor.Status.OK, second.get(5, TimeUnit.SECONDS).status());
		} finally {
			release.countDown();
		}
	}

	private String metadata(String ddl) {
		return mapper.writeValueAsString(Map.of("create_table_query", ddl)) + "\n";
	}
}
