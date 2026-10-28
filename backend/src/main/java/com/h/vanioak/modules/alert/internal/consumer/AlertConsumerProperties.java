package com.h.vanioak.modules.alert.internal.consumer;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

public record AlertConsumerProperties(boolean consumerEnabled,int retryMaxAttempts,List<Duration> retryDelays,
		Duration publishConfirmTimeout,Path fencePath,Path realtimeFencePath) {
	public AlertConsumerProperties {
		retryDelays=List.copyOf(retryDelays);
		if(retryMaxAttempts<0 || retryDelays.size()!=retryMaxAttempts || publishConfirmTimeout.isNegative()
				|| publishConfirmTimeout.toMillis()<1 || publishConfirmTimeout.compareTo(Duration.ofSeconds(5))>0
				|| retryDelays.stream().anyMatch(d -> d.isNegative() || d.toMillis()<1))
			throw new IllegalArgumentException("Invalid Alert consumer bounds");
		publishConfirmTimeout.toNanos();
		if(consumerEnabled) {
			if(fencePath==null || !fencePath.isAbsolute() || fencePath.getParent()==null || !Files.isDirectory(fencePath.getParent(),LinkOption.NOFOLLOW_LINKS))
				throw new IllegalArgumentException("Alert requires an absolute fence in an existing stable directory");
			try {
				Path resolved=fencePath.getParent().toRealPath().resolve(fencePath.getFileName());
				if(realtimeFencePath!=null && resolved.equals(realtimeFencePath.toAbsolutePath().getParent().toRealPath().resolve(realtimeFencePath.getFileName())))
					throw new IllegalArgumentException("Alert cannot share the Realtime fence");
			} catch(java.io.IOException ex) { throw new IllegalArgumentException("Alert fence directory unavailable"); }
		}
	}
	static AlertConsumerProperties from(Environment env) {
		String enabled=value(env,"consumer-enabled","ALERT_CONSUMER_ENABLED","false");
		if(!List.of("true","false").contains(enabled)) throw new IllegalArgumentException("Invalid Alert enabled flag");
		String delays=value(env,"retry-delays","ALERT_RETRY_DELAYS","5s,30s,120s");
		String fence=value(env,"fence-path","ALERT_FENCE_PATH","");String realtime=env.getProperty("vanioak.realtime.fence-path","");
		return new AlertConsumerProperties(Boolean.parseBoolean(enabled),Integer.parseInt(value(env,"retry-max-attempts","ALERT_RETRY_MAX_ATTEMPTS","3")),
				delays.isBlank()?List.of():Arrays.stream(delays.split(",",-1)).map(String::trim).map(DurationStyle::detectAndParse).toList(),
				DurationStyle.detectAndParse(value(env,"publish-confirm-timeout","ALERT_PUBLISH_CONFIRM_TIMEOUT","5s")),
				fence.isBlank()?null:Path.of(fence),realtime.isBlank()?null:Path.of(realtime));
	}
	private static String value(Environment env,String key,String alias,String fallback) {
		return env.getProperty("vanioak.alert."+key,env.getProperty(alias,fallback));
	}
}
