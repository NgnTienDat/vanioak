package com.h.vanioak.api.realtime;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class RealtimeController {
	static final String PATH = "/api/v1/realtime/stream";
	static final String CONNECTION = RealtimeController.class.getName() + ".connection";
	private final RealtimeApiService service;

	public RealtimeController(RealtimeApiService service) { this.service = service; }

	@GetMapping(value = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> stream(Authentication caller, HttpServletRequest request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(value = "application_id", required = false) UUID applicationId,
			@RequestParam(value = "environment_id", required = false) UUID environmentId,
			@RequestParam(value = "level", required = false) String level) {
		RealtimeSseConnection connection = new RealtimeSseConnection();
		var subscription = service.open(caller, authorization, applicationId, environmentId, level, connection);
		connection.bind(subscription);
		request.setAttribute(CONNECTION, connection);
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header(HttpHeaders.CACHE_CONTROL, "no-cache")
				.body(connection.emitter());
	}
}
