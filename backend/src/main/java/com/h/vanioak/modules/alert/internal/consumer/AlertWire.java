package com.h.vanioak.modules.alert.internal.consumer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class AlertWire {
	static final int INPUT_LIMIT=1_048_576, DLQ_LIMIT=2_097_152;
	private AlertWire() { }
	static JsonMapper mapper() {
		return JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(INPUT_LIMIT)
						.maxNestingDepth(64).maxTokenCount(262144).maxNameLength(4096)
						.maxNumberLength(4096).maxStringLength(INPUT_LIMIT).build()).build())
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
						DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
	}
	static byte[] encode(ObjectMapper mapper,Object value) {
		var sink=new Capped();
		mapper.writeValue(sink,value);
		return Arrays.copyOf(sink.bytes,sink.count);
	}
	private static final class Capped extends OutputStream {
		final byte[] bytes=new byte[DLQ_LIMIT]; int count;
		@Override public void write(int value) throws IOException {
			if(count==bytes.length) throw new IOException("Alert representation capacity exceeded");
			bytes[count++]=(byte)value;
		}
		@Override public void write(byte[] source,int offset,int length) throws IOException {
			if(length>bytes.length-count) throw new IOException("Alert representation capacity exceeded");
			System.arraycopy(source,offset,bytes,count,length);count+=length;
		}
	}
}
