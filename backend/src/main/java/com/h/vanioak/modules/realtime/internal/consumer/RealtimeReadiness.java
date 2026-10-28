package com.h.vanioak.modules.realtime.internal.consumer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RealtimeReadiness {
	private final AtomicBoolean registered = new AtomicBoolean();
	private final AtomicBoolean paused = new AtomicBoolean();
	public boolean ready() { return registered.get() && !paused.get(); }
	public void registered() { if (!paused.get()) registered.set(true); }
	public boolean pause() { boolean first = paused.compareAndSet(false, true); registered.set(false); return first; }
	public boolean paused() { return paused.get(); }
}
