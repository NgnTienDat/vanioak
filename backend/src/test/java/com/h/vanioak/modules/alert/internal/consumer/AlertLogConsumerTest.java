package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.Decision;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.Result;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.rabbitmq.client.Channel;

class AlertLogConsumerTest {
	final AlertEngineFacade engine=mock(AlertEngineFacade.class);
	final AlertFailurePublisher failures=mock(AlertFailurePublisher.class);
	final Channel channel=mock(Channel.class);
	final Runnable stop=mock(Runnable.class);
	AlertLogConsumer consumer;
	@BeforeEach void setup() {
		when(channel.isOpen()).thenReturn(true);when(engine.evaluate(any())).thenReturn(new Result(Decision.ACCOUNTED,null,0));
		consumer=new AlertLogConsumer(engine,new AlertEventDecoder(),failures,stop);consumer.registered(channel);
	}
	Message source(String body,long delivery) {
		var message=AlertEventDecoderTest.message(body);var p=message.getMessageProperties();p.setDeliveryTag(delivery);p.setConsumerQueue("alert.queue");p.setConsumerTag(consumer.consumerTag());return message;
	}
	@Test void everyCommittedDecisionIncludingDuplicatesAcknowledgesExactlyOnceAfterEvaluate() throws Exception {
		long tag=0;
		for(Decision decision:Decision.values()) {
			when(engine.evaluate(any())).thenReturn(new Result(decision,null,0));consumer.onMessage(source(AlertEventDecoderTest.valid(),++tag),channel);
		}
		var order=inOrder(engine,channel);for(long delivery=1;delivery<=4;delivery++){order.verify(engine).evaluate(any());order.verify(channel).basicAck(delivery,false);}
		verify(failures,never()).retry(any(),any());verify(failures,never()).deadLetter(any(),any(),any());assertFalse(consumer.paused());
	}
	@Test void commitBarrierPreventsEarlyAckAndAckRunsOnListenerThread() throws Exception {
		var entered=new CountDownLatch(1);var committed=new CountDownLatch(1);var listener=new AtomicReference<Thread>();
		when(engine.evaluate(any())).thenAnswer(call -> {listener.set(Thread.currentThread());entered.countDown();assertTrue(committed.await(2,TimeUnit.SECONDS));return new Result(Decision.OPENED,java.util.UUID.randomUUID(),2);});
		doAnswer(call -> {assertSame(listener.get(),Thread.currentThread());return null;}).when(channel).basicAck(anyLong(),anyBoolean());
		try(var threads=Executors.newSingleThreadExecutor()) {
			var run=threads.submit(() -> consumer.onMessage(source(AlertEventDecoderTest.valid(),1),channel));
			try {assertTrue(entered.await(2,TimeUnit.SECONDS));verify(channel,never()).basicAck(anyLong(),anyBoolean());}
			finally {committed.countDown();}
			run.get(2,TimeUnit.SECONDS);verify(channel).basicAck(1,false);
		}
	}
	@Test void transientCommitFailureWaitsForConfirmedRetryBeforeAck() throws Exception {
		when(engine.evaluate(any())).thenThrow(new IllegalStateException("commit failed"));var waiting=new CompletableFuture<Boolean>();var entered=new CountDownLatch(1);
		when(failures.retry(any(),any())).thenAnswer(call -> {entered.countDown();return waiting;});
		try(var threads=Executors.newSingleThreadExecutor()) {
			var run=threads.submit(() -> consumer.onMessage(source(AlertEventDecoderTest.valid(),1),channel));
			try {assertTrue(entered.await(2,TimeUnit.SECONDS));verify(channel,never()).basicAck(anyLong(),anyBoolean());}
			finally {waiting.complete(true);}
			run.get(2,TimeUnit.SECONDS);verify(channel).basicAck(1,false);verify(engine).evaluate(any());
		}
	}
	@Test void malformedAndUnsupportedGoOnlyToConfirmedDlq() throws Exception {
		when(failures.deadLetter(any(),any(),anyString())).thenReturn(CompletableFuture.completedFuture(true));
		consumer.onMessage(source("{",1),channel);consumer.onMessage(source(AlertEventDecoderTest.valid().replace("\"schema_version\":1","\"schema_version\":2"),2),channel);
		verify(failures).deadLetter(any(),any(),eq(AlertFailurePublisher.INVALID));verify(failures).deadLetter(any(),any(),eq(AlertFailurePublisher.UNSUPPORTED));
		verify(channel).basicAck(1,false);verify(channel).basicAck(2,false);verifyNoInteractions(engine);
	}
	@Test void failedHandoffLatchesStopAndNeverAcksOrHotRequeues() throws Exception {
		when(failures.deadLetter(any(),any(),anyString())).thenReturn(CompletableFuture.completedFuture(false));
		consumer.onMessage(source("{",1),channel);consumer.registered(mock(Channel.class));consumer.onMessage(source(AlertEventDecoderTest.valid(),2),channel);
		assertTrue(consumer.paused());verify(stop).run();verify(failures).close();verify(channel,never()).basicAck(anyLong(),anyBoolean());verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());verifyNoInteractions(engine);
	}
	@Test void capacityIsNotMalformedAndRetainsSourceWithoutTransfer() throws Exception {
		consumer.onMessage(source(" ".repeat(AlertWire.INPUT_LIMIT+1),1),channel);
		assertTrue(consumer.paused());verify(channel,never()).basicAck(anyLong(),anyBoolean());verify(failures,never()).deadLetter(any(),any(),anyString());verify(failures,never()).retry(any(),any());verifyNoInteractions(engine);
	}
	@Test void invalidRetryMetadataAndPermanentEngineInputFailureAreNotReevaluated() throws Exception {
		when(failures.retryCount(any())).thenReturn(-1);when(failures.deadLetter(any(),any(),anyString())).thenReturn(CompletableFuture.completedFuture(true));
		consumer.onMessage(source(AlertEventDecoderTest.valid(),1),channel);verifyNoInteractions(engine);verify(failures).deadLetter(any(),any(),eq(AlertFailurePublisher.INVALID_RETRY));
		when(failures.retryCount(any())).thenReturn(0);when(engine.evaluate(any())).thenThrow(new AlertException(ErrorCode.INVALID_REQUEST));
		consumer.onMessage(source(AlertEventDecoderTest.valid(),2),channel);verify(failures).deadLetter(any(),any(),eq(AlertFailurePublisher.INVALID));
	}
	@Test void wrongQueueTagChannelAndRepeatedSourceTagCannotAck() throws Exception {
		for(String kind:List.of("queue","tag","channel","unregistered")) {
			var c=new AlertLogConsumer(engine,new AlertEventDecoder(),failures,stop);if(!kind.equals("unregistered")) c.registered(channel);
			var m=source(AlertEventDecoderTest.valid(),1);m.getMessageProperties().setConsumerTag(c.consumerTag());
			if(kind.equals("queue"))m.getMessageProperties().setConsumerQueue("raw.queue");if(kind.equals("tag"))m.getMessageProperties().setConsumerTag("other");
			c.onMessage(m,kind.equals("channel")?mock(Channel.class):channel);assertTrue(c.paused());
		}
		verifyNoInteractions(engine);verify(channel,never()).basicAck(anyLong(),anyBoolean());
		var m=source(AlertEventDecoderTest.valid(),1);consumer.onMessage(m,channel);consumer.onMessage(m,channel);
		verify(channel,times(1)).basicAck(1,false);verify(engine,times(1)).evaluate(any());assertTrue(consumer.paused());
	}
	@Test void ackExceptionNeverRetriesThatDeliveryTag() throws Exception {
		doThrow(new IOException("uncertain ACK")).when(channel).basicAck(1,false);
		var m=source(AlertEventDecoderTest.valid(),1);consumer.onMessage(m,channel);consumer.onMessage(m,channel);
		assertTrue(consumer.paused());verify(channel,times(1)).basicAck(1,false);verify(engine,times(1)).evaluate(any());verify(failures,never()).retry(any(),any());
	}
	@Test void failedFutureAndStopCallbackFailureRemainUnacknowledged() throws Exception {
		when(failures.deadLetter(any(),any(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unknown")));
		doThrow(new IllegalStateException("stop failed")).when(stop).run();consumer.onMessage(source("[",1),channel);
		assertTrue(consumer.paused());verify(channel,never()).basicAck(anyLong(),anyBoolean());
	}
	@Test void proxyDetachmentDoesNotProveNativeSourceRetirement() {
		var proxy=mock(org.springframework.amqp.rabbit.connection.ChannelProxy.class);var nativeChannel=mock(Channel.class);
		when(proxy.isOpen()).thenReturn(true);when(nativeChannel.isOpen()).thenReturn(true);when(proxy.getTargetChannel()).thenReturn(nativeChannel);
		var c=new AlertLogConsumer(engine,new AlertEventDecoder(),failures,stop);c.registered(proxy);assertTrue(c.ready());
		when(proxy.getTargetChannel()).thenReturn(null);c.stop();assertFalse(c.sourceRetired());
		when(nativeChannel.isOpen()).thenReturn(false);assertTrue(c.sourceRetired());
	}
}
