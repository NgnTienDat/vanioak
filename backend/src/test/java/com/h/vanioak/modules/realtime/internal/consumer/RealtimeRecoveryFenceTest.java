package com.h.vanioak.modules.realtime.internal.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealtimeRecoveryFenceTest {
	@TempDir Path directory;
	@Test void newProcessCannotRearmPersistentMarkerOrClearIt() throws Exception {
		Path path = directory.resolve("realtime.fence");
		new RealtimeRecoveryFence(path).arm();
		String before = Files.readString(path);
		assertThrows(java.io.IOException.class, () -> new RealtimeRecoveryFence(path).arm());
		assertEquals(before, Files.readString(path));
	}
	@Test void missingRelativeAndNonexistentDirectoriesFailClosed() {
		assertThrows(java.io.IOException.class, () -> new RealtimeRecoveryFence(null).arm());
		assertThrows(java.io.IOException.class, () -> new RealtimeRecoveryFence(Path.of("relative")).arm());
		assertThrows(java.io.IOException.class, () -> new RealtimeRecoveryFence(directory.resolve("missing/file")).arm());
	}
}
