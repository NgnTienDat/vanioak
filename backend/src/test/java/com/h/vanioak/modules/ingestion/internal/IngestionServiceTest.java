package com.h.vanioak.modules.ingestion.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.VerificationResult;
import com.h.vanioak.modules.identity.api.ApplicationFacade.EnvironmentName;
import com.h.vanioak.modules.ingestion.api.ErrorCode;
import com.h.vanioak.modules.ingestion.api.IngestionException;
import com.h.vanioak.modules.ingestion.api.IngestionFacade.LogScope;
import com.h.vanioak.modules.ingestion.api.IngestionFacade.LogEntry;
import com.h.vanioak.modules.ingestion.api.events.RawLogBatchEvent;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class IngestionServiceTest {
	private final RawLogPublisher publisher = mock(RawLogPublisher.class);
	private final ApiKeyFacade identity = mock(ApiKeyFacade.class);
	private final Clock clock = mock(Clock.class);
	private final Instant start = Instant.parse("2026-10-08T10:00:00Z");
	private final AtomicReference<Instant> now = new AtomicReference<>(start);
	private final VerificationResult context = context(start.plusSeconds(30));
	private IngestionService service;

	@BeforeEach
	void setup() {
		when(clock.instant()).thenAnswer(call -> now.get());
		when(identity.verify(anyString())).thenReturn(context);
		service = new IngestionService(identity, publisher, Duration.ofSeconds(5), 2, clock);
	}

	@Test
	void localHitsAvoidIdentityAndDoNotExtendLocalTtl() {
		assertSame(context, service.authenticate("test-key"));
		now.set(start.plusSeconds(4));
		assertSame(context, service.authenticate("test-key"));
		verify(identity, times(1)).verify("test-key");
		now.set(start.plusSeconds(5));
		assertSame(context, service.authenticate("test-key"));
		verify(identity, times(2)).verify("test-key");
	}

	@Test
	void redisHitNearItsDeadlineDoesNotGainAnotherLocalTtl() {
		var nearExpiry = context(start.plusSeconds(6));
		when(identity.verify("test-key")).thenReturn(nearExpiry);
		service.authenticate("test-key");
		now.set(start.plusSeconds(5));
		service.authenticate("test-key");
		now.set(start.plusMillis(5999));
		service.authenticate("test-key");
		verify(identity, times(2)).verify("test-key");
		now.set(start.plusSeconds(6));
		failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.authenticate("test-key"));
		verify(identity, times(3)).verify("test-key");
		assertTrue(cache().isEmpty());
	}

	@Test
	void authenticatedLocalContextExpiringBeforeIngestRevalidatesOnceAndPublishesOnce() {
		now.set(context.validUntil().minusMillis(2));
		assertSame(context, service.authenticate("test-key"));
		now.set(context.validUntil().minusMillis(1));
		var authenticated = service.authenticate("test-key");
		assertSame(context, authenticated);
		assertTrue(authenticated.valid());
		var refreshed = context(context.validUntil().plusSeconds(30));
		when(identity.verify("test-key")).thenReturn(refreshed);
		now.set(context.validUntil().plusMillis(1));
		var result = service.ingest(authenticated, "test-key",
				List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null)));

		var capture = ArgumentCaptor.forClass(RawLogBatchEvent.class);
		verify(publisher, times(1)).publish(capture.capture());
		assertEquals(1, result.acceptedCount());
		assertEquals(result.requestId(), capture.getValue().payload().requestId());
		assertEquals(refreshed.applicationId(), capture.getValue().payload().applicationId());
		assertEquals(refreshed.environmentId(), capture.getValue().payload().environmentId());
		assertEquals(now.get(), capture.getValue().payload().receivedAt());
		verify(identity, times(2)).verify("test-key");
	}

	@Test
	void deadlineRevalidationRejectsInvalidCredentialsAndChangedBindingsWithoutPublishing() {
		now.set(context.validUntil());
		var refreshed = context(context.validUntil().plusSeconds(30));
		for (VerificationResult rejected : new VerificationResult[] {
				new VerificationResult(false, null, null, null, null, null),
				new VerificationResult(true, refreshed.applicationId(), refreshed.environmentId(), "other", EnvironmentName.DEV, refreshed.validUntil()),
				new VerificationResult(true, refreshed.applicationId(), refreshed.environmentId(), "example", EnvironmentName.TEST, refreshed.validUntil())}) {
			when(identity.verify("test-key")).thenReturn(rejected);
			var failure = assertThrows(IngestionException.class, () -> service.ingest(context, "test-key",
					List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null))));
			assertEquals(ErrorCode.INVALID_API_KEY, failure.getErrorCode());
			assertEquals(401, failure.getErrorCode().getStatus().value());
		}
		verify(identity, times(3)).verify("test-key");
		verifyNoInteractions(publisher);
	}

	@Test
	void deadlineRevalidationFailsClosedOnDependencyFailure() {
		now.set(context.validUntil());
		when(identity.verify("test-key")).thenThrow(new DataAccessResourceFailureException("Private dependency detail"));
		var failure = assertThrows(IngestionException.class, () -> service.ingest(context, "test-key", List.of()));
		assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.getErrorCode());
		assertEquals(503, failure.getErrorCode().getStatus().value());
		assertEquals("Service unavailable", failure.getMessage());
		verify(identity, times(1)).verify("test-key");
		verifyNoInteractions(publisher);
	}

	@Test
	void deadlineRevalidationRejectsMissingOrExpiredResultsWithoutRetry() {
		now.set(context.validUntil());
		for (VerificationResult unusable : new VerificationResult[] {null, context}) {
			when(identity.verify("test-key")).thenReturn(unusable);
			failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.ingest(context, "test-key", List.of()));
		}
		verify(identity, times(2)).verify("test-key");
		verifyNoInteractions(publisher);
	}

	@Test
	void refreshedContextExpiringAgainFailsClosedWithoutAnotherLookup() {
		var refreshed = context(context.validUntil().plusSeconds(1));
		when(identity.verify("test-key")).thenReturn(refreshed);
		when(clock.instant()).thenReturn(context.validUntil(), refreshed.validUntil().minusMillis(1), refreshed.validUntil());
		failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.ingest(context, "test-key", List.of()));
		verify(identity, times(1)).verify("test-key");
		verifyNoInteractions(publisher);
	}

	@Test
	void deadlineRevalidationBypassesEvenARefreshedLocalCache() {
		service.authenticate("test-key");
		now.set(context.validUntil());
		var refreshed = context(context.validUntil().plusSeconds(30));
		when(identity.verify("test-key")).thenReturn(refreshed);
		assertSame(refreshed, service.authenticate("test-key"));
		when(identity.verify("test-key")).thenReturn(new VerificationResult(false, null, null, null, null, null));
		failure(ErrorCode.INVALID_API_KEY, () -> service.ingest(context, "test-key", List.of()));
		verify(identity, times(3)).verify("test-key");
		verifyNoInteractions(publisher);
	}

	@Test
	void publicationFailureAfterRevalidationNeverTriggersRevalidationOrPublicationRetry() {
		var refreshed = context(context.validUntil().plusSeconds(30));
		when(identity.verify("test-key")).thenReturn(refreshed);
		now.set(context.validUntil());
		org.mockito.Mockito.doAnswer(call -> {
			now.set(refreshed.validUntil().plusMillis(1));
			throw new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE);
		}).when(publisher).publish(org.mockito.ArgumentMatchers.any());
		failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.ingest(context, "test-key",
				List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null))));
		verify(identity, times(1)).verify("test-key");
		verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void authenticatedLocalContextRemainingValidThroughIngestPublishesAndReturnsAcceptance() {
		now.set(context.validUntil().minusMillis(2));
		assertSame(context, service.authenticate("test-key"));
		var authenticated = service.authenticate("test-key");
		now.set(context.validUntil().minusMillis(1));
		var result = service.ingest(authenticated, "test-key",
				List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null)));

		assertEquals(1, result.acceptedCount());
		var capture = ArgumentCaptor.forClass(RawLogBatchEvent.class);
		verify(publisher, times(1)).publish(capture.capture());
		assertEquals(result.requestId(), capture.getValue().payload().requestId());
		assertEquals(context.applicationId(), capture.getValue().payload().applicationId());
		assertEquals(context.environmentId(), capture.getValue().payload().environmentId());
		assertEquals(now.get(), capture.getValue().payload().receivedAt());
		verify(identity, times(1)).verify("test-key");
	}

	@Test
	void expiryDecisionAndBindingValidationUseTheSameInstant() {
		var authenticated = service.authenticate("test-key");
		when(clock.instant()).thenReturn(context.validUntil().minusMillis(1), context.validUntil());
		var result = service.ingest(authenticated, "test-key",
				List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null)));
		assertEquals(1, result.acceptedCount());
		verify(identity, times(1)).verify("test-key");
		verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void boundsCacheSizeAndStoresOnlyFingerprintKeysAndVerifiedContext() {
		service.authenticate("key-one");
		service.authenticate("key-two");
		service.authenticate("key-one");
		service.authenticate("key-three");
		assertEquals(2, cache().size());
		assertTrue(cache().keySet().stream().allMatch(key -> key.toString().matches("[0-9a-f]{64}")));
		assertFalse(cache().toString().contains("key-one"));
		service.authenticate("key-one");
		verify(identity, times(1)).verify("key-one");
		service.authenticate("key-two");
		verify(identity, times(2)).verify("key-two");
		assertEquals(2, cache().size());
	}

	@Test
	void missingOrInvalidCredentialsAreRejectedAndNeverCached() {
		failure(ErrorCode.INVALID_API_KEY, () -> service.authenticate(null));
		failure(ErrorCode.INVALID_API_KEY, () -> service.authenticate(""));
		verifyNoInteractions(identity);
		when(identity.verify("invalid-key")).thenReturn(new VerificationResult(false, null, null, null, null, null));
		failure(ErrorCode.INVALID_API_KEY, () -> service.authenticate("invalid-key"));
		failure(ErrorCode.INVALID_API_KEY, () -> service.authenticate("invalid-key"));
		verify(identity, times(2)).verify("invalid-key");
		assertTrue(cache().isEmpty());
	}

	@Test
	void usableLocalHitSurvivesDependencyFailureButExpiredEntryFailsClosed() {
		service.authenticate("test-key");
		when(identity.verify(anyString())).thenThrow(new DataAccessResourceFailureException("Private failure detail"));
		assertSame(context, service.authenticate("test-key"));
		now.set(start.plusSeconds(5));
		var failure = assertThrows(IngestionException.class, () -> service.authenticate("test-key"));
		assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.getErrorCode());
		assertEquals("Service unavailable", failure.getMessage());
		assertTrue(cache().isEmpty());
	}

	@Test
	void nullIncompleteAndExpiredIdentityContextIsUnavailableWithoutGuessingDeadline() {
		for (VerificationResult invalid : new VerificationResult[] {null,
				new VerificationResult(true, context.applicationId(), context.environmentId(), null, EnvironmentName.DEV, context.validUntil()),
				new VerificationResult(true, context.applicationId(), context.environmentId(), "example", EnvironmentName.DEV, null),
				context(start)}) {
			when(identity.verify("test-key")).thenReturn(invalid);
			failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.authenticate("test-key"));
		}
		assertTrue(cache().isEmpty());
	}

	@Test
	void slowIdentityResponseCannotRenewItsOriginalDeadline() {
		when(identity.verify("test-key")).thenAnswer(call -> {
			now.set(start.plusSeconds(31));
			return context;
		});
		failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.authenticate("test-key"));
		assertTrue(cache().isEmpty());
	}

	@Test
	void bindingUsesExactApplicationNameAndCanonicalEnvironmentForEveryLog() {
		assertDoesNotThrow(() -> service.validateBinding(context,
				List.of(new LogScope("example", "DEV"), new LogScope("example", "dev"))));
		for (LogScope wrong : List.of(new LogScope("Example", "DEV"), new LogScope(" example", "DEV"),
				new LogScope("example", "TEST"), new LogScope("other", "dev")))
			failure(ErrorCode.INVALID_API_KEY, () -> service.validateBinding(context,
					List.of(new LogScope("example", "DEV"), wrong)));
		verifyNoInteractions(identity);
	}

	@Test
	void invalidCacheConfigurationFailsSafely() {
		for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)})
			assertThrows(IllegalArgumentException.class, () -> new IngestionService(identity, publisher, ttl, 2, clock));
		for (int size : new int[] {0, -1})
			assertThrows(IllegalArgumentException.class,
					() -> new IngestionService(identity, publisher, Duration.ofSeconds(5), size, clock));
	}

	@Test
	void singleAndBatchPrepareExactSchemaAndAuthenticatedIdsBeforeOnePublish() {
		var mapper = JsonMapper.builder().build();
		var metadata = Map.of("nested", mapper.readTree("[null,true,3,{\"a\":\"b\"}]"));
		var entry = new LogEntry("example", "dev", "source-host", "ERROR", "message", start.minusSeconds(2), "trace", metadata);
		var result = service.ingest(context, "test-key", List.of(entry, entry));
		var capture = ArgumentCaptor.forClass(RawLogBatchEvent.class);
		verify(publisher).publish(capture.capture());
		var event = capture.getValue();
		assertEquals(result.requestId(), event.payload().requestId());
		assertEquals(2, result.acceptedCount());
		assertEquals(context.applicationId(), event.payload().applicationId());
		assertEquals(context.environmentId(), event.payload().environmentId());
		assertEquals(start, event.occurredAt());
		assertEquals(start, event.payload().receivedAt());
		assertEquals(2, event.payload().logs().stream().map(RawLogBatchEvent.Log::eventId).distinct().count());
		var tree = mapper.valueToTree(event);
		assertEquals(6, tree.size());
		assertEquals("raw.log", tree.get("event_type").asString());
		assertEquals("ingestion", tree.get("producer").asString());
		assertEquals(1, tree.get("schema_version").asInt());
		assertEquals(5, tree.get("payload").size());
		var log = tree.get("payload").get("logs").get(0);
		assertEquals(7, log.size());
		assertEquals("source-host", log.get("host_ip").asString());
		assertEquals("ERROR", log.get("level").asString());
		assertEquals("message", log.get("message").asString());
		assertEquals(start.minusSeconds(2).toString(), log.get("timestamp").asString());
		assertEquals("trace", log.get("trace_id").asString());
		assertEquals(mapper.valueToTree(metadata), log.get("metadata"));
		var single = service.ingest(context, "test-key", List.of(new LogEntry("example", "DEV", "host", "INFO", "", start, "", null)));
		assertEquals(1, single.acceptedCount());
		assertFalse(single.requestId().equals(result.requestId()));
	}

	@Test
	void mismatchPublishesNothingAndPublisherFailureCannotReturnAcceptance() {
		failure(ErrorCode.INVALID_API_KEY, () -> service.ingest(context, "test-key",
				List.of(new LogEntry("other", "DEV", "host", "INFO", "message", start, "trace", null))));
		verifyNoInteractions(publisher);
		doThrow(new IngestionException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(publisher)
				.publish(org.mockito.ArgumentMatchers.any());
		failure(ErrorCode.DEPENDENCY_UNAVAILABLE, () -> service.ingest(context, "test-key",
				List.of(new LogEntry("example", "DEV", "host", "INFO", "message", start, "trace", null))));
	}

	private VerificationResult context(Instant deadline) {
		return new VerificationResult(true, UUID.randomUUID(), UUID.randomUUID(), "example", EnvironmentName.DEV, deadline);
	}

	private Map<?, ?> cache() { return (Map<?, ?>) ReflectionTestUtils.getField(service, "cache"); }

	private void failure(ErrorCode code, org.junit.jupiter.api.function.Executable operation) {
		assertEquals(code, assertThrows(IngestionException.class, operation).getErrorCode());
	}
}
