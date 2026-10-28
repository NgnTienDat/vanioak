package com.h.vanioak.modules.alert.internal.consumer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class AlertConsumerPropertiesTest {
	@TempDir Path directory;
	@Test void defaultsAreDisabledAndUseThreeDelays() {
		var p=AlertConsumerProperties.from(new MockEnvironment());assertFalse(p.consumerEnabled());assertNull(p.fencePath());
		assertEquals(3,p.retryMaxAttempts());assertEquals(List.of(Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofSeconds(120)),p.retryDelays());
		assertEquals(Duration.ofSeconds(5),p.publishConfirmTimeout());
	}
	@Test void environmentAliasesBindAndCanonicalValuesTakePrecedence() {
		var env=new MockEnvironment().withProperty("ALERT_CONSUMER_ENABLED","true").withProperty("ALERT_FENCE_PATH",directory.resolve("alert.fence").toString())
				.withProperty("ALERT_RETRY_MAX_ATTEMPTS","0").withProperty("ALERT_RETRY_DELAYS","").withProperty("ALERT_PUBLISH_CONFIRM_TIMEOUT","1s");
		var p=AlertConsumerProperties.from(env);assertTrue(p.consumerEnabled());assertEquals(0,p.retryMaxAttempts());assertEquals(Duration.ofSeconds(1),p.publishConfirmTimeout());
		env.withProperty("vanioak.alert.consumer-enabled","false");assertFalse(AlertConsumerProperties.from(env).consumerEnabled());
	}
	@Test void invalidBoundsFlagsAndDelayCountsFailClosed() {
		for(String[] pair:List.of(new String[]{"retry-max-attempts","-1"},new String[]{"retry-max-attempts","2"},new String[]{"retry-delays","0s,30s,120s"},
				new String[]{"retry-delays","5s,,120s"},new String[]{"publish-confirm-timeout","6s"},new String[]{"publish-confirm-timeout","0s"},
				new String[]{"consumer-enabled","yes"},new String[]{"retry-delays","P999999999999999999D"}))
			assertThrows(RuntimeException.class,() -> AlertConsumerProperties.from(new MockEnvironment().withProperty("vanioak.alert."+pair[0],pair[1])));
	}
	@Test void enabledRequiresStableAbsoluteParentAndDifferentRealtimeFence() {
		for(String path:List.of("relative.fence",directory.resolve("missing/alert.fence").toString()))
			assertThrows(IllegalArgumentException.class,() -> AlertConsumerProperties.from(new MockEnvironment().withProperty("ALERT_CONSUMER_ENABLED","true").withProperty("ALERT_FENCE_PATH",path)));
		String path=directory.resolve("same.fence").toString();
		assertThrows(IllegalArgumentException.class,() -> AlertConsumerProperties.from(new MockEnvironment().withProperty("ALERT_CONSUMER_ENABLED","true").withProperty("ALERT_FENCE_PATH",path).withProperty("vanioak.realtime.fence-path",path)));
	}
	@Test void retryListIsImmutable() {
		var source=new java.util.ArrayList<>(List.of(Duration.ofSeconds(1)));var p=new AlertConsumerProperties(false,1,source,Duration.ofSeconds(1),null,null);source.clear();assertEquals(1,p.retryDelays().size());
	}
}
