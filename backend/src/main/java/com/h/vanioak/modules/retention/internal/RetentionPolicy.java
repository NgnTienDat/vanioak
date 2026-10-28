package com.h.vanioak.modules.retention.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RetentionPolicy {
	private final int days;

	public RetentionPolicy(@Value("${LOG_RETENTION_DAYS:7}") String configuredDays) {
		if (!"7".equals(configuredDays))
			throw new IllegalArgumentException("LOG_RETENTION_DAYS must be 7 for MVP");
		this.days = 7;
	}

	public int days() {
		return days;
	}
}
