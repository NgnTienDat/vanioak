package com.h.vanioak.modules.realtime.internal.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class RealtimePropertiesTest {
	@Test
	void corsCannotBecomeWildcard() {
		assertTrue(new RealtimeProperties(false, null).allowedOrigins().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new RealtimeProperties(true, List.of("*")));
		assertThrows(IllegalArgumentException.class, () -> new RealtimeProperties(true, List.of("https://*.example.com")));
	}
}
