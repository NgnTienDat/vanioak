package com.h.vanioak.modules.alert.internal.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AlertConsumerLifecycle implements SmartLifecycle,AutoCloseable {
	private final ConnectionFactory connection;
	private final AlertConsumerProperties properties;
	private final SimpleMessageListenerContainer container;
	private final AlertFailurePublisher publisher;
	private final AlertLogConsumer consumer;
	private final AtomicBoolean attempted=new AtomicBoolean(),stopped=new AtomicBoolean(),closing=new AtomicBoolean();
	private volatile boolean armed;
	private volatile boolean shutdownSettled;
	private final Object listenerLifecycle=new Object();
	private final ThreadPoolExecutor control=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task -> {
		var thread=new Thread(task,"alert-consumer-control");thread.setDaemon(true);return thread;
	});
	public AlertConsumerLifecycle(ConnectionFactory connection,AlertEngineFacade engine,AlertConsumerProperties properties) {
		this.connection=connection;this.properties=properties;
		publisher=new AlertFailurePublisher(new RabbitTemplate(connection),properties.retryDelays(),properties.publishConfirmTimeout());
		consumer=new AlertLogConsumer(engine,new AlertEventDecoder(),publisher,this::stop);
		container=AlertConsumerConfiguration.preparedContainer(connection,consumer);container.afterPropertiesSet();
	}
	AlertConsumerLifecycle(ConnectionFactory connection,AlertConsumerProperties properties,SimpleMessageListenerContainer container,
			AlertFailurePublisher publisher,AlertLogConsumer consumer) {
		this.connection=connection;this.properties=properties;this.container=container;this.publisher=publisher;this.consumer=consumer;
	}
	@Override public void start() {
		if(!properties.consumerEnabled() || !attempted.compareAndSet(false,true) || stopped.get())return;
		try {
			if(Files.exists(properties.fencePath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException("Existing Alert recovery fence");
			if(!(connection instanceof CachingConnectionFactory caching) || !caching.isPublisherConfirms() || caching.isSimplePublisherConfirms()
					|| !caching.isPublisherReturns())throw new IllegalStateException("Alert requires native correlated confirms and returns");
			// Passive existence only. Full arguments/bindings/size compatibility require operator preflight.
			var probe=new RabbitTemplate(connection);
			probe.execute(channel -> {
				for(String queue:new String[]{"alert.queue","alert.retry.queue","dead-letter.queue"})channel.queueDeclarePassive(queue);
				for(String exchange:new String[]{"critical.exchange","dead-letter.exchange"})channel.exchangeDeclarePassive(exchange);
				return null;
			});
			if(stopped.get()) return;
			try(var file=FileChannel.open(properties.fencePath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
				var bytes=ByteBuffer.wrap("ALERT_ARMED_REVIEW_REQUIRED\n".getBytes(StandardCharsets.US_ASCII));while(bytes.hasRemaining())file.write(bytes);file.force(true);
			}
			armed=true;
			synchronized(listenerLifecycle) { if(!stopped.get())container.start(); }
		} catch(Exception ex) { log.warn("Alert startup held: {}",ex.getClass().getSimpleName());stop(); }
	}
	public boolean ready() { return armed && !stopped.get() && consumer.ready(); }
	public boolean shutdownSettled() { return shutdownSettled; }
	@Override public boolean isRunning() { return armed && !stopped.get() && container.isRunning(); }
	@Override public boolean isAutoStartup() { return properties.consumerEnabled(); }
	@Override public int getPhase() { return Integer.MAX_VALUE-100; }
	@Override public void stop() {
		if(!stopped.compareAndSet(false,true))return;
		consumer.stop();
		try { control.execute(() -> {try {synchronized(listenerLifecycle) {container.stop();}}finally {publisher.close();}}); }
		catch(java.util.concurrent.RejectedExecutionException ex) { publisher.close();log.warn("Alert stop control unavailable; recovery fence retained"); }
	}
	@Override public void stop(Runnable callback) { close();callback.run(); }
	@Override public void close() {
		if(!closing.compareAndSet(false,true))return;
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);stop();control.shutdown();
		try {
			boolean settled=control.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
			publisher.close();settled=publisher.awaitStopped(Math.max(0,deadline-System.nanoTime())) && settled && !publisher.occupied() && consumer.sourceRetired();
			shutdownSettled=settled;
			if(!settled)log.warn("Alert shutdown not settled; native ownership and recovery fence retained");
		} catch(InterruptedException ex) {Thread.currentThread().interrupt();publisher.close();}
	}
}
