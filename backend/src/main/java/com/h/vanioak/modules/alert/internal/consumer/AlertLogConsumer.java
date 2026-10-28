package com.h.vanioak.modules.alert.internal.consumer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AlertLogConsumer implements ChannelAwareMessageListener {
	private final AlertEngineFacade engine;
	private final AlertEventDecoder decoder;
	private final AlertFailurePublisher failures;
	private final Runnable stopConsumption;
	private final AtomicBoolean paused=new AtomicBoolean();
	private final String tag="alert-"+UUID.randomUUID();
	private volatile Channel source;
	private volatile Channel nativeSource;
	private long lastDelivery;
	public AlertLogConsumer(AlertEngineFacade engine,AlertEventDecoder decoder,AlertFailurePublisher failures,Runnable stopConsumption) {
		this.engine=engine;this.decoder=decoder;this.failures=failures;this.stopConsumption=stopConsumption;
	}
	public String consumerTag() { return tag; }
	public void registered(Channel channel) {
		if(paused.get()) return;
		if(channel==null) { pause();return; }
		if(source!=null && source!=channel) { pause();return; }
		Channel nativeChannel=channel instanceof org.springframework.amqp.rabbit.connection.ChannelProxy proxy?proxy.getTargetChannel():channel;
		if(nativeChannel==null) { pause();return; }
		nativeSource=nativeChannel;source=channel;channel.addShutdownListener(signal -> pause());
	}
	@Override public synchronized void onMessage(Message message,Channel channel) {
		var p=message.getMessageProperties();long delivery=p.getDeliveryTag();
		if(paused.get() || channel!=source || !channel.isOpen() || delivery<=lastDelivery
				|| !"alert.queue".equals(p.getConsumerQueue()) || !tag.equals(p.getConsumerTag())) { pause();return; }
		lastDelivery=delivery;
		try {
			var decoded=decoder.decode(message);boolean accounted=false;
			if(decoded.kind()==AlertEventDecoder.Kind.CAPACITY) { pause();return; }
			if(failures.retryCount(message)<0) accounted=handoff(failures.deadLetter(message,decoded,AlertFailurePublisher.INVALID_RETRY));
			else if(decoded.kind()!=AlertEventDecoder.Kind.VALID) accounted=handoff(failures.deadLetter(message,decoded,
					decoded.kind()==AlertEventDecoder.Kind.UNSUPPORTED?AlertFailurePublisher.UNSUPPORTED:AlertFailurePublisher.INVALID));
			else {
				try { var result=engine.evaluate(decoded.event());accounted=result!=null && result.decision()!=null; }
				catch(AlertException ex) {
					accounted=handoff(ex.getErrorCode()==ErrorCode.INVALID_REQUEST?failures.deadLetter(message,decoded,AlertFailurePublisher.INVALID):failures.retry(message,decoded));
				} catch(RuntimeException ex) { accounted=handoff(failures.retry(message,decoded)); }
			}
			if(!accounted || paused.get() || channel!=source || !channel.isOpen()) { pause();return; }
			channel.basicAck(delivery,false);
		} catch(Exception ex) { if(ex instanceof InterruptedException) Thread.currentThread().interrupt();pause(); }
	}
	private boolean handoff(java.util.concurrent.CompletionStage<Boolean> result) throws Exception {
		return Boolean.TRUE.equals(result.toCompletableFuture().get(6,TimeUnit.SECONDS));
	}
	private void pause() {
		if(paused.compareAndSet(false,true)) {
			failures.close();
			try { stopConsumption.run(); }
			catch(RuntimeException ex) { log.warn("Alert consumer stop failed: {}",ex.getClass().getSimpleName()); }
		}
	}
	public void stop() { pause(); }
	public boolean paused() { return paused.get(); }
	public boolean ready() { return !paused.get() && source!=null && source.isOpen(); }
	boolean sourceRetired() {
		try {
			return source==null || (nativeSource!=null && !nativeSource.isOpen());
		} catch(RuntimeException ex) { return false; }
	}
}
