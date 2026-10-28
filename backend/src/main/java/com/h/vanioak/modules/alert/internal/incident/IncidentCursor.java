package com.h.vanioak.modules.alert.internal.incident;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.ErrorCode;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Filters;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Scope;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class IncidentCursor {
	private final ObjectMapper mapper;
	record Key(Instant time, UUID id) { }

	String context(Scope scope, Filters filters) {
		var values = new LinkedHashMap<String,Object>();
		values.put("actor", scope.actorId());
		values.put("environments", scope.environmentIds().stream().map(UUID::toString).sorted().toList());
		values.put("app", scope.requestedApplication()); values.put("env", scope.requestedEnvironment());
		values.put("status", filters.status()); values.put("level", filters.level());
		values.put("from", filters.from() == null ? null : filters.from().toString());
		values.put("to", filters.to() == null ? null : filters.to().toString());
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(values))); }
		catch (Exception ex) { throw new AlertException(ErrorCode.UNAVAILABLE); }
	}

	String encode(Key key, String context) {
		var values = new LinkedHashMap<String,Object>(); values.put("v",1); values.put("time",key.time().toString());
		values.put("id",key.id().toString()); values.put("context",context);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(values));
	}

	Key decode(String token, String context) {
		try {
			if (token.isEmpty() || token.length()>2048) throw new IllegalArgumentException();
			var bytes = Base64.getUrlDecoder().decode(token);
			var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
			if (!node.isObject() || node.size()!=4 || !"1".equals(node.get("v").toString())
					|| !node.get("time").isString() || !node.get("id").isString() || !node.get("context").isString()
					|| !context.equals(node.get("context").asString())) throw new IllegalArgumentException();
			var key = new Key(Instant.parse(node.get("time").asString()), UUID.fromString(node.get("id").asString()));
			Math.multiplyExact(key.time().getEpochSecond(),1_000_000L);
			if (key.time().getNano()%1000!=0 || !encode(key,context).equals(token)) throw new IllegalArgumentException();
			return key;
		} catch (Exception ex) { throw new AlertException(ErrorCode.INVALID_CURSOR); }
	}
}
