package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class AlertFailurePublisherTest {
	final RabbitTemplate rabbit=mock(RabbitTemplate.class);
	final PublisherCallbackChannel channel=mock(PublisherCallbackChannel.class);
	final List<Message> bodies=new CopyOnWriteArrayList<>();
	final List<CorrelationData> correlations=new CopyOnWriteArrayList<>();
	final List<String> routes=new CopyOnWriteArrayList<>();
	@BeforeEach void setup() {
		when(channel.isOpen()).thenReturn(true);
		doAnswer(call -> ((RabbitOperations.OperationsCallback<?>)call.getArgument(0)).doInRabbit(rabbit)).when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
		doAnswer(call -> ((ChannelCallback<?>)call.getArgument(0)).doInRabbit(channel)).when(rabbit).execute(any(ChannelCallback.class));
	}
	AlertFailurePublisher publisher() { return new AlertFailurePublisher(rabbit,List.of(Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofSeconds(120)),Duration.ofMillis(50)); }
	void sends(boolean ack,boolean returned) {
		doAnswer(call -> {
			Message body=call.getArgument(2);CorrelationData c=call.getArgument(3);bodies.add(body);correlations.add(c);routes.add(call.getArgument(0)+"/"+call.getArgument(1));
			if(returned)c.setReturned(new ReturnedMessage(body,312,"NO_ROUTE",call.getArgument(0),call.getArgument(1)));
			c.getFuture().complete(new CorrelationData.Confirm(ack,null));return null;
		}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
	}
	@Test void threeRetriesPreserveBodyIdentityAndUseOnlyBoundedCounterThenDlq() throws Exception {
		sends(true,false);Message source=AlertEventDecoderTest.message(AlertEventDecoderTest.valid());source.getMessageProperties().setMessageId("original-id");
		source.getMessageProperties().setHeader("x-processing-retry-count",99);var decoded=new AlertEventDecoder().decode(source);
		try(var p=publisher()) {
			for(int count=0;count<3;count++) {
				assertTrue(p.retry(source,decoded).toCompletableFuture().get(2,TimeUnit.SECONDS));Message copy=bodies.getLast();
				assertEquals(Integer.valueOf(count+1),copy.getMessageProperties().<Integer>getHeader(AlertFailurePublisher.RETRY_COUNT));assertArrayEquals(source.getBody(),copy.getBody());
				assertEquals("original-id",copy.getMessageProperties().getMessageId());assertEquals(List.of("5000","30000","120000").get(count),copy.getMessageProperties().getExpiration());
				assertEquals(MessageDeliveryMode.PERSISTENT,copy.getMessageProperties().getDeliveryMode());assertNull(copy.getMessageProperties().getHeader("x-processing-retry-count"));source=copy;
			}
			assertTrue(p.retry(source,decoded).toCompletableFuture().get(2,TimeUnit.SECONDS));
			assertEquals(List.of("/alert.retry.queue","/alert.retry.queue","/alert.retry.queue","dead-letter.exchange/dead-letter.log"),routes);
			var root=AlertWire.mapper().readTree(bodies.getLast().getBody());assertEquals(6,root.size());assertEquals(5,root.get("payload").size());
			assertEquals("ALERT_RETRY_EXHAUSTED",root.get("payload").get("reason").asString());assertEquals("alert",root.get("producer").asString());
			assertEquals(decoded.original(),root.get("payload").get("original_message"));verify(rabbit).setMandatory(true);
		}
	}
	@Test void invalidCountersNeverBecomeFreshAttempts() {
		try(var p=publisher()) {
			var source=AlertEventDecoderTest.message("{}");assertEquals(0,p.retryCount(source));
			for(Object value:List.of("1",true,1.0,-1,4,Long.MAX_VALUE)) {source.getMessageProperties().setHeader(AlertFailurePublisher.RETRY_COUNT,value);assertEquals(-1,p.retryCount(source));}
		}
	}
	@Test void malformedBytesUseLosslessBase64AndValidMessageIdBeforeFallback() throws Exception {
		sends(true,false);var source=new Message(new byte[]{(byte)0xff,0,10},new org.springframework.amqp.core.MessageProperties());String id=UUID.randomUUID().toString();source.getMessageProperties().setMessageId(id);
		try(var p=publisher()) {
			assertTrue(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
			var root=AlertWire.mapper().readTree(bodies.getFirst().getBody());assertEquals(id,root.get("event_id").asString());
			assertEquals(root.get("occurred_at"),root.get("payload").get("failed_at"));assertArrayEquals(source.getBody(),Base64.getDecoder().decode(root.get("payload").get("original_message").get("body_base64").asString()));
		}
	}
	@Test void originalObjectNumbersAndEnvelopeIdArePreserved() throws Exception {
		sends(true,false);String number="9".repeat(100);var source=AlertEventDecoderTest.message(AlertEventDecoderTest.valid().replace("ERROR","INFO").replace("\"payload\":","\"n\":"+number+",\"payload\":"));
		source.getMessageProperties().setMessageId(UUID.randomUUID().toString());var decoded=new AlertEventDecoder().decode(source);
		try(var p=publisher()) {
			assertTrue(p.deadLetter(source,decoded,AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
			var root=AlertWire.mapper().readTree(bodies.getFirst().getBody());assertEquals(AlertEventDecoderTest.ID.toString(),root.get("event_id").asString());
			assertEquals(number,root.get("payload").get("original_message").get("n").bigIntegerValue().toString());
		}
	}
	@Test void nackAndMandatoryReturnDespiteAckNeverSucceed() throws Exception {
		for(boolean returned:List.of(false,true)) {
			sends(returned,returned);var source=AlertEventDecoderTest.message("[");try(var p=publisher()) {
				assertFalse(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));assertFalse(p.occupied());
			}
		}
	}
	@Test void timeoutKeepsNativeSlotAndRejectsFurtherTransfersWithoutCancellingCorrelation() throws Exception {
		doAnswer(call -> {correlations.add(call.getArgument(3));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
		var source=AlertEventDecoderTest.message("[");try(var p=publisher()) {
			assertFalse(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
			assertTrue(p.occupied());assertFalse(correlations.getFirst().getFuture().isCancelled());
			assertTrue(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().isCompletedExceptionally());assertEquals(1,correlations.size());
		}
	}
	@Test void callerCancellationDoesNotReleaseBlockedNativeSendOrCreateReplacement() throws Exception {
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(call -> {correlations.add(call.getArgument(3));entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return null;})
				.when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
		var source=AlertEventDecoderTest.message("[");try(var p=publisher()) {
			var stage=p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID);var completion=stage.toCompletableFuture();var view=stage.toCompletableFuture();
			assertTrue(entered.await(2,TimeUnit.SECONDS));view.cancel(true);assertTrue(p.occupied());assertFalse(correlations.getFirst().getFuture().isCancelled());
			assertTrue(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().isCompletedExceptionally());
			release.countDown();completion.get(2,TimeUnit.SECONDS);
		} finally {release.countDown();}
	}
	@Test void completeDlqOverflowNeverPublishesPartialBody() {
		var source=AlertEventDecoderTest.message("{}");var root=AlertWire.mapper().createObjectNode().put("huge","\u0000".repeat(400000));
		try(var p=publisher()) {
			var decoded=new AlertEventDecoder.Decoded(AlertEventDecoder.Kind.INVALID,null,root,source.getBody());
			assertTrue(p.deadLetter(source,decoded,AlertFailurePublisher.INVALID).toCompletableFuture().isCompletedExceptionally());
			verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
		}
	}
	@Test void zeroRetryPolicyImmediatelyUsesExhaustedDlq() throws Exception {
		sends(true,false);var source=AlertEventDecoderTest.message(AlertEventDecoderTest.valid());
		try(var p=new AlertFailurePublisher(rabbit,List.of(),Duration.ofSeconds(1))) {
			assertTrue(p.retry(source,new AlertEventDecoder().decode(source)).toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals("dead-letter.exchange/dead-letter.log",routes.getFirst());
		}
	}
	@Test void slotIsReleasedOnlyForProvenOwnChannelRetirement() throws Exception {
		doAnswer(call -> null).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
		when(channel.isOpen()).thenReturn(false);var source=AlertEventDecoderTest.message("[");
		for(int pending:List.of(0,1)) {
			when(channel.getPendingConfirmsCount()).thenReturn(pending);
			try(var p=publisher()) {
				assertFalse(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
				assertEquals(pending!=0,p.occupied());
			}
		}
	}
	@Test void sendFailureBeforeNativeRegistrationDoesNotInventSlotCompletion() throws Exception {
		doThrow(new IllegalStateException("send unknown")).when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
		var source=AlertEventDecoderTest.message("[");try(var p=publisher()) {
			assertFalse(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
			assertTrue(p.occupied());assertTrue(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().isCompletedExceptionally());
		}
	}
	@Test void exactInputBudgetBase64PreservesAllBytesWithinCompleteDlqBudget() throws Exception {
		sends(true,false);byte[] raw=new byte[AlertWire.INPUT_LIMIT];raw[0]=(byte)0xff;
		var source=new Message(raw,new org.springframework.amqp.core.MessageProperties());
		try(var p=publisher()) {
			assertTrue(p.deadLetter(source,new AlertEventDecoder().decode(source),AlertFailurePublisher.INVALID).toCompletableFuture().get(2,TimeUnit.SECONDS));
			assertTrue(bodies.getFirst().getBody().length<=AlertWire.DLQ_LIMIT);
			var encoded=new tools.jackson.databind.ObjectMapper().readTree(bodies.getFirst().getBody()).get("payload").get("original_message").get("body_base64").asString();
			assertEquals(4*((raw.length+2)/3),encoded.length());assertArrayEquals(raw,Base64.getDecoder().decode(encoded));
		}
	}
	@Test void outputCapIncludesJsonFramingAtExactEdgeAndRejectsOneByteOverflow() {
		assertEquals(AlertWire.DLQ_LIMIT,AlertWire.encode(AlertWire.mapper(),"x".repeat(AlertWire.DLQ_LIMIT-2)).length);
		assertThrows(RuntimeException.class,() -> AlertWire.encode(AlertWire.mapper(),"x".repeat(AlertWire.DLQ_LIMIT-1)));
	}
}
