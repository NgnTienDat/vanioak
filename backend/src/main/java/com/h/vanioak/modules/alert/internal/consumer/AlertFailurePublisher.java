package com.h.vanioak.modules.alert.internal.consumer;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.rabbitmq.client.Channel;

/** Confirmed Alert failure transfer; lifecycle ownership gates activation. */
public final class AlertFailurePublisher implements AutoCloseable {
	static final String RETRY_COUNT="x-alert-retry-count";
	static final String INVALID="ALERT_INVALID_CRITICAL_EVENT",UNSUPPORTED="ALERT_UNSUPPORTED_SCHEMA_VERSION",
			INVALID_RETRY="INVALID_RETRY_METADATA",EXHAUSTED="ALERT_RETRY_EXHAUSTED";
	private final RabbitTemplate rabbit;
	private final List<Duration> delays;
	private final Duration timeout;
	private final Clock clock;
	private final AtomicBoolean occupied=new AtomicBoolean(),stopped=new AtomicBoolean();
	private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task -> {
		var thread=new Thread(task,"alert-failure-transfer");thread.setDaemon(true);return thread;
	});
	public AlertFailurePublisher(RabbitTemplate dedicated,List<Duration> delays,Duration timeout) {
		this(dedicated,delays,timeout,Clock.systemUTC());
	}
	AlertFailurePublisher(RabbitTemplate dedicated,List<Duration> delays,Duration timeout,Clock clock) {
		if(timeout==null || timeout.isNegative() || timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(5))>0 || delays==null
				|| delays.stream().anyMatch(d -> d==null || d.isNegative() || d.toMillis()<1)) throw new IllegalArgumentException("Invalid Alert transfer bounds");
		this.rabbit=dedicated;this.delays=List.copyOf(delays);this.timeout=timeout;this.clock=clock;
		dedicated.setMandatory(true);dedicated.setReturnsCallback(returned -> { });
	}
	public int retryCount(Message message) {
		var headers=message.getMessageProperties().getHeaders();if(!headers.containsKey(RETRY_COUNT)) return 0;
		Object value=headers.get(RETRY_COUNT);
		if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return -1;
		long count=((Number)value).longValue();return count>=0 && count<=delays.size()?(int)count:-1;
	}
	public CompletionStage<Boolean> retry(Message original,AlertEventDecoder.Decoded decoded) {
		int count=retryCount(original);
		if(count<0 || count==delays.size()) return deadLetter(original,decoded,count<0?INVALID_RETRY:EXHAUSTED);
		var properties=properties(original.getMessageProperties().getMessageId());
		properties.setHeader(RETRY_COUNT,count+1);properties.setExpiration(Long.toString(delays.get(count).toMillis()));
		return transfer("","alert.retry.queue",new Message(original.getBody().clone(),properties));
	}
	public CompletionStage<Boolean> deadLetter(Message original,AlertEventDecoder.Decoded decoded,String reason) {
		if(decoded.kind()==AlertEventDecoder.Kind.CAPACITY || !List.of(INVALID,UNSUPPORTED,INVALID_RETRY,EXHAUSTED).contains(reason))
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unavailable Alert failure representation"));
		try {
			UUID id=decoded.original()!=null && decoded.original().get("event_id")!=null && decoded.original().get("event_id").isString()
					?AlertEventDecoder.canonical(decoded.original().get("event_id").asString()):null;
			if(id==null) id=AlertEventDecoder.canonical(original.getMessageProperties().getMessageId());
			if(id==null) id=UUID.randomUUID();
			var mapper=AlertWire.mapper();String failed=clock.instant().toString();var root=mapper.createObjectNode();
			root.put("event_id",id.toString()).put("event_type","dead-letter.log").put("occurred_at",failed).put("producer","alert").put("schema_version",1);
			var payload=root.putObject("payload");payload.put("event_id",id.toString()).put("reason",reason).put("field","$").put("failed_at",failed);
			payload.set("original_message",decoded.original()!=null?decoded.original():mapper.createObjectNode().put("body_base64",Base64.getEncoder().encodeToString(decoded.raw())));
			return transfer("dead-letter.exchange","dead-letter.log",new Message(AlertWire.encode(mapper,root),properties(id.toString())));
		} catch(RuntimeException ex) { return CompletableFuture.failedFuture(new IllegalStateException("Alert failure representation unavailable")); }
	}
	private MessageProperties properties(String id) {
		var p=new MessageProperties();p.setMessageId(id);p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		p.setContentEncoding("UTF-8");p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);return p;
	}
	private CompletionStage<Boolean> transfer(String exchange,String key,Message message) {
		if(stopped.get() || !occupied.compareAndSet(false,true)) return CompletableFuture.failedFuture(new IllegalStateException("Alert transfer unavailable"));
		var result=new CompletableFuture<Boolean>();long deadline=System.nanoTime()+timeout.toNanos();
		try {
			worker.execute(() -> {
				var correlation=new CorrelationData(UUID.randomUUID().toString());Channel[] owned={null};boolean success=false;
				try {
					if(stopped.get() || System.nanoTime()>=deadline) { occupied.set(false);result.complete(false);return; }
					success=Boolean.TRUE.equals(rabbit.invoke(operations -> rabbit.execute(channel -> {
						owned[0]=channel instanceof ChannelProxy proxy?proxy.getTargetChannel():channel;
						try {
							operations.send(exchange,key,message,correlation);
							long remaining=deadline-System.nanoTime();if(remaining<=0) throw new java.util.concurrent.TimeoutException();
							var confirm=correlation.getFuture().get(remaining,TimeUnit.NANOSECONDS);
							return confirm!=null && confirm.ack() && correlation.getReturned()==null;
						} catch(Exception ex) {
							if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
							RabbitUtils.setPhysicalCloseRequired(channel,true);return false;
						}
					})));
				} catch(RuntimeException ex) { /* Reconcile native ownership below, not from a thrown send. */ }
				boolean terminal=correlation.getFuture().isDone() && !correlation.getFuture().isCancelled()
						&& !correlation.getFuture().isCompletedExceptionally() && correlation.getFuture().getNow(null)!=null;
				boolean retired=owned[0] instanceof PublisherCallbackChannel callback && !owned[0].isOpen() && callback.getPendingConfirmsCount()==0;
				if(terminal || retired) occupied.set(false);else stopped.set(true);
				result.complete(success && terminal);
			});
		} catch(RuntimeException ex) { occupied.set(false);result.complete(false); }
		return result.minimalCompletionStage();
	}
	boolean occupied() { return occupied.get(); }
	boolean awaitStopped(long nanos) throws InterruptedException { return worker.awaitTermination(Math.max(0,nanos),TimeUnit.NANOSECONDS); }
	@Override public void close() { stopped.set(true);worker.shutdown(); }
}
