package com.h.vanioak.api.realtime;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.h.vanioak.modules.realtime.api.RealtimeFacade;

final class RealtimeSseConnection implements RealtimeFacade.Sink {
	private final SseEmitter emitter = new SseEmitter(300_000L);
	private final CompletableFuture<Void> terminated = new CompletableFuture<>();
	private volatile RealtimeFacade.Subscription subscription;

	RealtimeSseConnection() {
		emitter.onCompletion(() -> { terminated.complete(null); closeSubscription(); });
		emitter.onTimeout(this::closeSubscription);
		emitter.onError(error -> closeSubscription());
	}

	SseEmitter emitter() { return emitter; }
	void bind(RealtimeFacade.Subscription value) { subscription = value; }
	void activate() { if (subscription != null) subscription.activate(); }
	void abort() { terminated.complete(null); closeSubscription(); }
	private void closeSubscription() { if (subscription != null) subscription.close(); }

	@Override
	public void send(String eventName, String json) throws IOException {
		emitter.send(SseEmitter.event().name(eventName).data(json));
	}

	@Override
	public void heartbeat() throws IOException { emitter.send(SseEmitter.event().comment("heartbeat")); }

	@Override
	public CompletionStage<Void> close() {
		emitter.complete();
		return terminated;
	}
}
