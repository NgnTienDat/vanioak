package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AlertIncidentSseHarnessTest {
 @Test void resolvedParserAndCountsAreStrict(){
  var mapper=new ObjectMapper();var id=UUID.randomUUID();var time=Instant.parse("2026-10-27T15:33:18Z");String wire="event: incident.resolved\ndata: {\"incident_id\":\""+id+"\",\"status\":\"RESOLVED\",\"resolved_at\":\""+time+"\"}\n\n";var frame=AlertResolvedSseNativeIT.parseResolved(wire,mapper);AlertResolvedSseNativeIT.assertResolved(List.of(frame),id,time);assertNull(AlertResolvedSseNativeIT.parseResolved(": ping\n\n",mapper));for(String bad:List.of(wire.replace("RESOLVED","OPEN"),wire.replace("incident.resolved","incident.created"),wire.replace("}\n",",\"extra\":1}\n"),"event: incident.resolved\ndata: nope\n\n"))assertThrows(RuntimeException.class,()->AlertResolvedSseNativeIT.parseResolved(bad,mapper));assertThrows(AssertionError.class,()->AlertResolvedSseNativeIT.assertResolved(List.of(),id,time));assertThrows(AssertionError.class,()->AlertResolvedSseNativeIT.assertResolved(List.of(frame,frame),id,time));assertThrows(AssertionError.class,()->AlertResolvedSseNativeIT.assertResolved(List.of(frame),UUID.randomUUID(),time));
 }
 @Test void privateTargetGuardRejectsHistoricalDatabaseAndRedisZero(){
  String url=AlertResolvedSseNativeIT.URL,role=AlertResolvedSseNativeIT.DB;AlertResolvedSseNativeIT.requireTarget(url,role,"15");assertThrows(IllegalStateException.class,()->AlertResolvedSseNativeIT.requireTarget(url.replace(role,"processing_test"),role,"15"));assertThrows(IllegalStateException.class,()->AlertResolvedSseNativeIT.requireTarget(url,"processing_test","15"));assertThrows(IllegalStateException.class,()->AlertResolvedSseNativeIT.requireTarget(url,role,"0"));
 }
 @Test void injectedResolvedParserReplaysTheSameJournal() throws Exception {
  UUID id=UUID.randomUUID();Instant time=Instant.parse("2026-10-27T15:33:18Z");String wire="event: incident.resolved\ndata: {\"incident_id\":\""+id+"\",\"status\":\"RESOLVED\",\"resolved_at\":\""+time+"\"}\n\n";var input=new TerminalInput(wire,new IOException("closed"));try(var p=new AlertIncidentSseNativeIT.Probe(input,directory.resolve(UUID.randomUUID()+".journal"),mapper,AlertResolvedSseNativeIT::parseResolved)){p.start();assertTrue(input.waiting.await(5,TimeUnit.SECONDS));p.beginPlannedStop();finished(p,input);AlertResolvedSseNativeIT.assertResolved(p.capturedFrames(true),id,time);}
 }

	@TempDir Path directory;
	final ObjectMapper mapper=new ObjectMapper();
	final UUID environment=UUID.fromString("00000000-0000-0000-0000-000000000001");
	final Instant lastSeen=Instant.parse("2026-10-27T15:33:18.328330Z");
	List<AlertIncidentSseNativeIT.Frame> goodFrames() {
		var created=mapper.createObjectNode().put("incident_id","incident").put("application_id","app").put("environment_id",environment.toString())
				.put("level","ERROR").put("status","OPEN").put("message","café Ω").put("error_count",2);
		var updated=mapper.createObjectNode().put("incident_id","incident").put("error_count",3).put("last_seen_at",lastSeen.toString());
		return List.of(new AlertIncidentSseNativeIT.Frame("incident.created",created),new AlertIncidentSseNativeIT.Frame("incident.updated",updated));
	}
	String wire() {return goodFrames().stream().map(f -> "event: "+f.name()+"\ndata: "+f.data()+"\n\n").collect(java.util.stream.Collectors.joining());}
	AlertIncidentSseNativeIT.Probe probe(TerminalInput input) throws Exception {
		var p=new AlertIncidentSseNativeIT.Probe(input,directory.resolve(UUID.randomUUID()+".journal"),mapper);p.start();assertTrue(input.waiting.await(5,TimeUnit.SECONDS));return p;
	}
	void finished(AlertIncidentSseNativeIT.Probe p,TerminalInput input) throws Exception {input.release.countDown();p.reader.join(1000);assertFalse(p.reader.isAlive());}
	@Test void acceptsIncidentFramesAndHeartbeatWithoutStartingContext() {
		var mapper=new ObjectMapper();assertNull(AlertIncidentSseNativeIT.parse(": heartbeat\n\n",mapper));
		for(String name:new String[]{"incident.created","incident.updated"})assertEquals(name,AlertIncidentSseNativeIT.parse("event: "+name+"\ndata: {\"incident_id\":\"synthetic\"}\n\n",mapper).name());
	}
	@Test void rejectsUnexpectedWireFieldsAndTypes() {
		var mapper=new ObjectMapper();for(String block:new String[]{"event: log\ndata: {}\n\n","id: fake\nevent: incident.created\ndata: {}\n\n","event: incident.updated\n\n"})assertThrows(IllegalArgumentException.class,() -> AlertIncidentSseNativeIT.parse(block,mapper));
	}
	@Test void acceptsOnlyVerifiedPlannedBodyClosureAndKeepsFailureEvidence() throws Exception {
		var error=new IOException("closed");var input=new TerminalInput(wire(),error);
		try(var p=probe(input)) {
			p.beginPlannedStop();finished(p,input);
			assertThrows(AssertionError.class,() -> p.capturedFrames(false));
			var captured=p.capturedFrames(true);assertEquals(goodFrames(),captured);assertSame(error,p.failure.get());
			assertThrows(UnsupportedOperationException.class,() -> captured.clear());
			AlertIncidentSseNativeIT.assertFrames(captured,List.of(),"café Ω","app",environment,lastSeen);
		}
	}
	@Test void earlyClosedIOExceptionCannotBeReclassifiedByLaterStop() throws Exception {
		var input=new TerminalInput(wire(),new IOException("closed"));try(var p=probe(input)) {
			finished(p,input);assertThrows(AssertionError.class,p::beginPlannedStop);assertThrows(AssertionError.class,() -> p.capturedFrames(true));
		}
	}
	@Test void plannedStopDoesNotAcceptOtherTransportOrParserErrors() throws Exception {
		for(Exception error:new Exception[]{new IOException("connection reset"),new java.net.SocketException("closed"),new IOException("closed",new IllegalStateException("cause")),new IOException("closed",new IOException("connection reset")),new IOException("closed",new java.net.SocketException("closed")),new IOException("closed",new java.io.EOFException("EOF reached while reading")),new IOException("closed",new IOException("connection reset",new java.io.EOFException("EOF reached while reading"))),new IOException("closed",new IOException("chunked transfer encoding, state: READING_DATA",new java.io.EOFException("EOF reached while reading"))),new IOException("closed",new IOException("chunked transfer encoding, state: READING_LENGTH",new java.io.EOFException("unexpected EOF"))),new IllegalArgumentException("malformed")}) {
			var input=new TerminalInput(wire(),error);try(var p=probe(input)){p.beginPlannedStop();finished(p,input);assertThrows(AssertionError.class,() -> p.capturedFrames(true));}
		}
	}
	@Test void partialTerminalFrameIsNotExpectedClosure() throws Exception {
		var input=new TerminalInput(wire()+"event: incident.updated\ndata: ",new IOException("closed"));try(var p=probe(input)){p.beginPlannedStop();finished(p,input);assertThrows(AssertionError.class,() -> p.capturedFrames(true));}
	}
	@Test void eofRequiresPlannedStopAndCompleteJournal() throws Exception {
		var early=new TerminalInput(wire(),null);try(var p=probe(early)){finished(p,early);assertThrows(AssertionError.class,() -> p.capturedFrames(true));}
		var normal=new TerminalInput(wire(),null);try(var p=probe(normal)){p.beginPlannedStop();finished(p,normal);assertEquals(goodFrames(),p.capturedFrames(true));}
	}
	@Test void frameAssertionsStillRejectMissingAdditionalUnauthorizedAndAlteredPayloads() {
		var good=goodFrames();AlertIncidentSseNativeIT.assertFrames(good,List.of(),"café Ω","app",environment,lastSeen);
		for(var invalid:List.of(List.<AlertIncidentSseNativeIT.Frame>of(),List.of(good.get(0)),List.of(good.get(0),good.get(1),good.get(1)),List.of(good.get(0),good.get(0))))
			assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(invalid,List.of(),"café Ω","app",environment,lastSeen));
		assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(good,List.of(good.get(0)),"café Ω","app",environment,lastSeen));
		assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(good,List.of(),"café Ω","other-app",environment,lastSeen));
		assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(good,List.of(),"changed","app",environment,lastSeen));
		assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(good,List.of(),"café Ω","app",UUID.randomUUID(),lastSeen));
		assertThrows(AssertionError.class,() -> AlertIncidentSseNativeIT.assertFrames(good,List.of(),"café Ω","app",environment,lastSeen.plusSeconds(1)));
	}
	@Test void completedJournalCannotBeChangedWithoutFailing() throws Exception {
		var input=new TerminalInput(wire(),new IOException("closed"));try(var p=probe(input)) {
			p.beginPlannedStop();finished(p,input);java.nio.file.Files.writeString(p.path,wire()+"event: incident.updated\ndata: {}\n\n",StandardCharsets.UTF_8);
			assertThrows(AssertionError.class,() -> p.capturedFrames(true));
		}
	}
 @Test void jdkBodySubscriberClosedWrapperIsTerminalOnlyAfterVerifiedStop() throws Exception {
  var subscriber=java.net.http.HttpResponse.BodySubscribers.ofInputStream();subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){}});
  var body=subscriber.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS);var parsed=new CountDownLatch(2);
  try(var p=new AlertIncidentSseNativeIT.Probe(body,directory.resolve("jdk-close.journal"),mapper,(block,json)->{var frame=AlertIncidentSseNativeIT.parse(block,json);if(frame!=null)parsed.countDown();return frame;})) {
   p.start();subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(wire().getBytes(StandardCharsets.UTF_8))));assertTrue(parsed.await(5,TimeUnit.SECONDS));assertEquals(goodFrames(),p.capturedFrames(false));p.beginPlannedStop();
   subscriber.onError(new IOException("closed"));p.reader.join(1000);assertFalse(p.reader.isAlive());assertNotNull(p.failure.get());assertNotNull(p.failure.get().getCause(),"JDK wraps the upstream closure");assertThrows(AssertionError.class,()->p.capturedFrames(false));assertEquals(goodFrames(),p.capturedFrames(true));assertTrue(java.nio.file.Files.exists(p.path.resolveSibling("jdk-close.journal.terminal.json")));
  }
 }
 @Test void jdkChunkBoundaryEofRequiresVerifiedPlannedStopAndKeepsJournal() throws Exception {
  var subscriber=java.net.http.HttpResponse.BodySubscribers.ofInputStream();subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){}});
  var body=subscriber.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS);var parsed=new CountDownLatch(2);
  try(var p=new AlertIncidentSseNativeIT.Probe(body,directory.resolve("jdk-chunk-eof.journal"),mapper,(block,json)->{var frame=AlertIncidentSseNativeIT.parse(block,json);if(frame!=null)parsed.countDown();return frame;})) {
   p.start();subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(wire().getBytes(StandardCharsets.UTF_8))));assertTrue(parsed.await(5,TimeUnit.SECONDS));var before=p.capturedFrames(false);p.beginPlannedStop();
   var upstream=new IOException("chunked transfer encoding, state: READING_LENGTH",new java.io.EOFException("EOF reached while reading"));subscriber.onError(upstream);p.reader.join(1000);assertFalse(p.reader.isAlive());assertSame(upstream,p.failure.get().getCause());
   assertThrows(AssertionError.class,()->p.capturedFrames(false));var after=p.capturedFrames(true);assertEquals(before,after);assertThrows(UnsupportedOperationException.class,after::clear);
  }
 }
 @Test void chunkBoundaryEofBeforeStopStillFails() throws Exception {
  var error=new IOException("closed",new IOException("chunked transfer encoding, state: READING_LENGTH",new java.io.EOFException("EOF reached while reading")));
  var input=new TerminalInput(wire(),error);try(var p=probe(input)){finished(p,input);assertThrows(AssertionError.class,p::beginPlannedStop);assertThrows(AssertionError.class,()->p.capturedFrames(true));assertSame(error,p.failure.get());}
 }
 @Test void chunkBoundaryEofCannotHidePartialTerminalData() throws Exception {
  var error=new IOException("closed",new IOException("chunked transfer encoding, state: READING_LENGTH",new java.io.EOFException("EOF reached while reading")));
  var input=new TerminalInput(wire(),error,"event: incident.updated\ndata: ");try(var p=probe(input)){p.beginPlannedStop();finished(p,input);assertThrows(AssertionError.class,()->p.capturedFrames(true));}
 }
 @Test void livePhaseChecksCompleteJournalBeforeShutdown() throws Exception {
  var input=new TerminalInput(wire(),new IOException("closed"));try(var p=probe(input)){assertEquals(goodFrames(),p.capturedFrames(false));java.nio.file.Files.writeString(p.path,wire().substring(0,wire().length()-1),StandardCharsets.UTF_8);assertThrows(AssertionError.class,()->p.capturedFrames(false));assertThrows(AssertionError.class,p::beginPlannedStop);}
 }
 @Test void postStopPartialMalformedOrAdditionalDataStillFails() throws Exception {
  for(String suffix:List.of("event: incident.updated\ndata: ","event: incident.updated\ndata: invalid\n\n","event: incident.updated\ndata: {}\n\n")) {
   var input=new TerminalInput(wire(),new IOException("closed"),suffix);try(var p=probe(input)){p.beginPlannedStop();finished(p,input);assertThrows(AssertionError.class,()->p.capturedFrames(true));}
  }
 }
 @Test void completeHeartbeatMayFinishDuringNormalShutdown() throws Exception {
  var input=new TerminalInput(wire(),new IOException("closed",new IOException("closed")),": heartbeat\n\n");try(var p=probe(input)){p.beginPlannedStop();assertThrows(AssertionError.class,()->p.capturedFrames(true));finished(p,input);assertEquals(goodFrames(),p.capturedFrames(true));}
 }
 @Test void preStopJournalPrefixCannotBeReplacedEvenWithSameDataFrames() throws Exception {
  var input=new TerminalInput(": before\n\n"+wire(),new IOException("closed"));try(var p=probe(input)){p.beginPlannedStop();finished(p,input);java.nio.file.Files.writeString(p.path,": changed\n\n"+wire(),StandardCharsets.UTF_8);assertThrows(AssertionError.class,()->p.capturedFrames(true));}
 }
 @Test void failureObservedBeforeStopCannotCrossThePhaseLock() throws Exception {
  var input=new TerminalInput(wire(),new IOException("closed"));try(var p=probe(input)) {
   synchronized(p){input.release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(p.reader.getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.onSpinWait();assertEquals(Thread.State.BLOCKED,p.reader.getState());p.beginPlannedStop();}
   p.reader.join(1000);assertFalse(p.reader.isAlive());assertThrows(AssertionError.class,()->p.capturedFrames(true));
  }
 }

	static final class TerminalInput extends InputStream {
		final ByteArrayInputStream prefix,afterStop;final Exception terminal;
		final CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
		TerminalInput(String prefix,Exception terminal){this(prefix,terminal,"");}
		TerminalInput(String prefix,Exception terminal,String suffix){this.prefix=new ByteArrayInputStream(prefix.getBytes(StandardCharsets.UTF_8));this.afterStop=new ByteArrayInputStream(suffix.getBytes(StandardCharsets.UTF_8));this.terminal=terminal;}
		@Override public int read() throws IOException {
			int b=prefix.read();if(b>=0)return b;waiting.countDown();
			try {if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Controlled input deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted",e);}
			int next=afterStop.read();if(next>=0)return next;
			if(terminal instanceof IOException e)throw e;if(terminal instanceof RuntimeException e)throw e;return -1;
		}
		@Override public void close(){release.countDown();}
	}
}
