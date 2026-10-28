package com.h.vanioak.modules.alert.internal.engine;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.h.vanioak.modules.alert.api.AlertEngineFacade;
import lombok.extern.slf4j.Slf4j;

@Component
@ConditionalOnProperty(name = "vanioak.alert.resolver-enabled", havingValue = "true")
@Slf4j
public class AlertResolutionScheduler {
	private final AlertEngineFacade engine;
	private final Duration interval;
	private final ScheduledExecutorService worker;

	@Autowired
	public AlertResolutionScheduler(AlertEngineFacade engine, @Value("${vanioak.alert.resolve-interval:1s}") Duration interval) {
		this(engine, interval, Executors.newSingleThreadScheduledExecutor(task -> {
			var thread = new Thread(task, "alert-resolver"); thread.setDaemon(true); return thread;
		}));
	}

	AlertResolutionScheduler(AlertEngineFacade engine, Duration interval, ScheduledExecutorService worker) {
		if (interval == null || interval.isNegative() || interval.toMillis() < 1)
			throw new IllegalArgumentException("Alert resolution interval must be positive");
		this.engine = engine; this.interval = interval; this.worker = worker;
	}

	@PostConstruct
	void start() { worker.scheduleWithFixedDelay(this::resolve, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS); }

	private void resolve() {
		try { engine.resolveInactive(100); }
		catch (RuntimeException ex) { log.warn("Alert resolution failed, type={}", ex.getClass().getSimpleName()); }
	}

	@PreDestroy
	void close() {
		worker.shutdownNow();
		try {
			if (!worker.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Alert resolver did not terminate within shutdown budget");
		}
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
	}
}
