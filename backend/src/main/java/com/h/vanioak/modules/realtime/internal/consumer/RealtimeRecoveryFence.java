package com.h.vanioak.modules.realtime.internal.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class RealtimeRecoveryFence {
	private final Path path;
	public RealtimeRecoveryFence(Path path) { this.path = path; }

	public void arm() throws IOException {
		if (path == null || !path.isAbsolute() || !Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Realtime requires an existing absolute fence directory");
		// Never clear this marker automatically, including clean shutdown. No log bodies are stored.
		try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer bytes = ByteBuffer.wrap("REALTIME_ARMED_REVIEW_REQUIRED\n".getBytes(StandardCharsets.US_ASCII));
			while (bytes.hasRemaining()) channel.write(bytes);
			channel.force(true);
		}
	}
}
