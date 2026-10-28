package com.h.vanioak.modules.realtime.internal.consumer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RealtimeJson {
	public static final int INPUT_LIMIT = 1_048_576;
	public static final int FRAME_LIMIT = 1_048_576;
	public static final int DLQ_LIMIT = 2_097_152;
	private RealtimeJson() { }

	public static JsonMapper reader() {
		return JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(INPUT_LIMIT)
						.maxNestingDepth(64).maxTokenCount(262144).maxNameLength(4096)
						.maxNumberLength(4096).maxStringLength(INPUT_LIMIT).build()).build())
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
						DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
						DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
	}

	public static byte[] encode(ObjectMapper mapper, Object value, int limit) {
		var stream = new Capped(limit);
		try {
			mapper.writeValue(stream, value);
			return Arrays.copyOf(stream.buffer, stream.count);
		} catch (RuntimeException ex) {
			throw new CapacityException();
		}
	}

	public static final class CapacityException extends RuntimeException {
		public CapacityException() { super("Realtime representation unavailable within capacity"); }
	}

	private static final class Capped extends OutputStream {
		private final byte[] buffer;
		private int count;
		Capped(int limit) { buffer = new byte[limit]; }
		@Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
		@Override public void write(byte[] bytes, int offset, int length) throws IOException {
			if (length > buffer.length - count) throw new IOException("Realtime capacity exceeded");
			System.arraycopy(bytes, offset, buffer, count, length);
			count += length;
		}
	}
}
