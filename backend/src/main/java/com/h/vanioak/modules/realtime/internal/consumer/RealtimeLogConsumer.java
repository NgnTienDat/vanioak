package com.h.vanioak.modules.realtime.internal.consumer;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStoppedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.SmartLifecycle;

import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;

public final class RealtimeLogConsumer implements ChannelAwareMessageListener, SmartLifecycle, AutoCloseable {
	private final SimpleMessageListenerContainer container;
	private final RealtimeEventDecoder decoder;
	private final RealtimeFailurePublisher failures;
	private final RealtimeService service;
	private final RealtimeReadiness readiness;
	private final RealtimeRecoveryFence fence;
	private final String tag = "realtime-" + UUID.randomUUID();
	private final AtomicBoolean attempted = new AtomicBoolean();
	private final AtomicBoolean armed = new AtomicBoolean();
	private final ThreadPoolExecutor control = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(1), task -> { var thread = new Thread(task, "realtime-control"); thread.setDaemon(true); return thread; });
	private volatile Channel sourceChannel;
	private long lastDeliveryTag;

	public RealtimeLogConsumer(SimpleMessageListenerContainer container, RealtimeEventDecoder decoder,
			RealtimeFailurePublisher failures, RealtimeService service, RealtimeReadiness readiness,
			RealtimeRecoveryFence fence) {
		this.container = container;
		this.decoder = decoder;
		this.failures = failures;
		this.service = service;
		this.readiness = readiness;
		this.fence = fence;
		container.setConsumerTagStrategy(queue -> tag);
		container.setMessageListener(this);
		container.setErrorHandler(error -> pause());
		// ConsumeOk's source is the native InternalConsumer, not the listener container.
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ConsumeOkEvent ok && "realtime.queue".equals(ok.getQueue())
					&& tag.equals(ok.getConsumerTag()) && ok.getSource() instanceof DefaultConsumer consumer
					&& armed.get() && !readiness.paused()) {
				if (sourceChannel != null && sourceChannel != consumer.getChannel()) { pause(); return; }
				sourceChannel = consumer.getChannel();
				sourceChannel.addShutdownListener(signal -> pause());
				readiness.registered();
			} else if (event instanceof ListenerContainerConsumerFailedEvent || event instanceof AsyncConsumerStoppedEvent) {
				pause();
			}
		});
	}

	@Override
	public void start() {
		if (!attempted.compareAndSet(false, true) || readiness.paused()) return;
		try {
			fence.arm();
			armed.set(true);
			container.start();
		} catch (Exception ex) { pause(); }
	}

	@Override
	public synchronized void onMessage(Message message, Channel channel) {
		long delivery = message.getMessageProperties().getDeliveryTag();
		if (!readiness.ready() || channel != sourceChannel || !channel.isOpen() || delivery <= lastDeliveryTag
				|| !"realtime.queue".equals(message.getMessageProperties().getConsumerQueue())) {
			pause(); return;
		}
		lastDeliveryTag = delivery;
		try {
			var decoded = decoder.decode(message);
			boolean accounted = switch (decoded.kind()) {
				case VALID -> service.publishLog(decoded.event()).accounted();
				case INVALID, UNSUPPORTED -> Boolean.TRUE.equals(failures.publish(decoded,
						message.getMessageProperties().getMessageId()).toCompletableFuture().get(30, TimeUnit.SECONDS));
				case CAPACITY -> false;
			};
			if (!accounted || !readiness.ready() || channel != sourceChannel || !channel.isOpen()) {
				pause(); return;
			}
			channel.basicAck(delivery, false);
		} catch (Exception ex) {
			if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
			pause();
		}
	}

	private void pause() {
		if (!readiness.pause()) return;
		service.unavailable();
		failures.close();
		try { control.execute(container::stop); }
		catch (java.util.concurrent.RejectedExecutionException ex) { /* The persistent armed fence still denies restart. */ }
	}

	@Override public boolean isAutoStartup() { return true; }
	@Override public boolean isRunning() { return armed.get() && !readiness.paused(); }
	@Override public int getPhase() { return Integer.MAX_VALUE - 100; }
	@Override public void stop() { pause(); }
	@Override public void close() { pause(); control.shutdown(); }
}
