package com.h.vanioak.api.alert;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.h.vanioak.api.alert.dto.IncidentResponse;
import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.alert.api.AlertRuleFacade.Level;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Filters;
import com.h.vanioak.modules.alert.api.IncidentReadFacade.Status;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/api/v1/incidents")
@SecurityRequirement(name = "bearerAuth") @RequiredArgsConstructor
public class IncidentController {
	private final IncidentApiService api;

	@GetMapping
	public ApiResponse<IncidentResponse.Page> list(Authentication caller,
			@RequestParam(required=false) UUID application_id, @RequestParam(required=false) UUID environment_id,
			@RequestParam(required=false) Status status, @RequestParam(required=false) Level level,
			@RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
			@RequestParam(required=false) String cursor, @RequestParam(defaultValue="50") int limit) {
		return ApiResponse.success(api.list(caller,application_id,environment_id,new Filters(status,level,from,to,cursor,limit)));
	}

	@GetMapping("/{incidentId}")
	public ApiResponse<IncidentResponse> detail(Authentication caller, @PathVariable UUID incidentId) {
		return ApiResponse.success(api.detail(caller,incidentId));
	}
}
