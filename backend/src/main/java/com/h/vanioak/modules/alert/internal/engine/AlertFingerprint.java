package com.h.vanioak.modules.alert.internal.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

final class AlertFingerprint {
	private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n\\f\\x0B]+");
	private AlertFingerprint() { }

	static String of(Level level, String message) {
		String normalized = WHITESPACE.matcher(message).replaceAll(" ");
		if (normalized.startsWith(" ")) normalized = normalized.substring(1);
		if (normalized.endsWith(" ")) normalized = normalized.substring(0, normalized.length() - 1);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest((level.name() + "\n" + normalized).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
}
