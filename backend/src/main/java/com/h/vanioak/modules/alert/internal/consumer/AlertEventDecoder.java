package com.h.vanioak.modules.alert.internal.consumer;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import com.h.vanioak.modules.alert.api.AlertEngineFacade.CriticalEvent;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;

public final class AlertEventDecoder {
	public enum Kind { VALID, INVALID, UNSUPPORTED, CAPACITY }
	public record Decoded(Kind kind,CriticalEvent event,JsonNode original,byte[] raw) { }

	public Decoded decode(Message message) {
		byte[] body=message.getBody();
		if(body.length>AlertWire.INPUT_LIMIT) return new Decoded(Kind.CAPACITY,null,null,null);
		byte[] raw=body.clone();JsonNode root;
		try {
			String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
			root=AlertWire.mapper().readTree(text);
		} catch(StreamConstraintsException | InputCoercionException | NumberFormatException ex) {
			return new Decoded(Kind.CAPACITY,null,null,null);
		} catch(CharacterCodingException | StreamReadException | MismatchedInputException ex) {
			return new Decoded(Kind.INVALID,null,null,raw);
		} catch(RuntimeException ex) { return new Decoded(Kind.CAPACITY,null,null,null); }
		if(root==null || !root.isObject()) return new Decoded(Kind.INVALID,null,null,raw);
		try {
			UUID id=uuid(root,"event_id");Instant.parse(text(root,"occurred_at"));
			if(!"critical.log".equals(text(root,"event_type")) || !"processing".equals(text(root,"producer")))
				throw new IllegalArgumentException();
			var payload=root.get("payload");
			if(payload==null || !payload.isObject()) throw new IllegalArgumentException();
			var version=root.get("schema_version");
			if(version==null || !version.isIntegralNumber()) throw new IllegalArgumentException();
			if(!BigInteger.ONE.equals(version.bigIntegerValue())) return new Decoded(Kind.UNSUPPORTED,null,root,raw);
			if(!id.equals(uuid(payload,"event_id"))) throw new IllegalArgumentException();
			uuid(payload,"application_id");UUID env=uuid(payload,"environment_id");
			Level level=Level.valueOf(text(payload,"level"));
			String messageText=text(payload,"message");Instant.parse(text(payload,"timestamp"));text(payload,"trace_id");
			Instant received=Instant.parse(text(payload,"received_at"));
			return new Decoded(Kind.VALID,new CriticalEvent(id,env,level,messageText,received),root,raw);
		} catch(RuntimeException ex) { return new Decoded(Kind.INVALID,null,root,raw); }
	}
	static UUID canonical(String value) {
		try { UUID id=UUID.fromString(value);return id.toString().equalsIgnoreCase(value)?id:null; }
		catch(RuntimeException ex) { return null; }
	}
	private static UUID uuid(JsonNode node,String key) {
		UUID id=canonical(text(node,key));if(id==null) throw new IllegalArgumentException();return id;
	}
	private static String text(JsonNode node,String key) {
		var value=node.get(key);if(value==null || !value.isString()) throw new IllegalArgumentException();return value.asString();
	}
}
