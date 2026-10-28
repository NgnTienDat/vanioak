package com.h.vanioak.modules.alert.internal.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;

class AlertFingerprintTest {
	@Test void allSixAsciiWhitespaceKindsNormalizeIdentically() {
		assertEquals(AlertFingerprint.of(Level.ERROR,"Database timeout"),
				AlertFingerprint.of(Level.ERROR," \t\r\nDatabase\f\u000Btimeout \t"));
		assertEquals(64,AlertFingerprint.of(Level.ERROR,"").length());
		assertEquals(AlertFingerprint.of(Level.ERROR,""),AlertFingerprint.of(Level.ERROR," \r\n\t"));
	}
	@Test void preservesCaseNonAsciiWhitespaceAndOtherControlCharacters() {
		String expected=AlertFingerprint.of(Level.ERROR,"Database timeout");
		assertNotEquals(expected,AlertFingerprint.of(Level.ERROR,"database timeout"));
		assertNotEquals(expected,AlertFingerprint.of(Level.ERROR,"Database\u00a0timeout"));
		assertNotEquals(expected,AlertFingerprint.of(Level.ERROR,"\u0000Database timeout"));
		assertNotEquals(expected,AlertFingerprint.of(Level.CRITICAL,"Database timeout"));
	}
	@Test void fingerprintIsLowercaseSha256OfUtf8LevelNewlineMessage() throws Exception {
		assertEquals("4eb10a85e3ddb06812b683469ea2047cc9130a36df3083adc9fda5c5a6cb0e0c",
				AlertFingerprint.of(Level.ERROR,"Database timeout"));
		String unicode = "Unicode \u03b1 \ud83d\ude80";
		String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(("ERROR\n"+unicode).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertEquals(expected,AlertFingerprint.of(Level.ERROR,unicode));
	}
}
