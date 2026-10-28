package com.h.vanioak.api.alert;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.h.vanioak.api.alert.dto.AlertRuleRequests;
import com.h.vanioak.api.alert.dto.AlertRuleResponse;
import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/alert-rules")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AlertRuleController {
	private final AlertRuleApiService api;

	@GetMapping
	public ApiResponse<AlertRuleResponse.Page> list(Authentication caller,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		var page = api.list(caller, cursor, limit);
		return ApiResponse.success(new AlertRuleResponse.Page(page.items().stream().map(AlertRuleResponse::from).toList(), page.nextCursor()));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<AlertRuleResponse>> create(Authentication caller,
			@Valid @RequestBody AlertRuleRequests.Create request) {
		var rule = api.create(caller, new AlertRuleFacade.Create(request.environmentId(), request.level(), request.threshold(),
				request.windowSeconds(), request.cooldownSeconds(), request.resolveAfterSeconds(), request.enabled()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(AlertRuleResponse.from(rule)));
	}

	@PatchMapping("/{ruleId}")
	public ApiResponse<AlertRuleResponse> update(Authentication caller, @PathVariable UUID ruleId,
			@Valid @RequestBody AlertRuleRequests.Update request) {
		return ApiResponse.success(AlertRuleResponse.from(api.update(caller, ruleId,
				new AlertRuleFacade.Update(request.threshold(), request.windowSeconds(), request.cooldownSeconds(),
						request.resolveAfterSeconds(), request.enabled()))));
	}

	@DeleteMapping("/{ruleId}")
	public ApiResponse<AlertRuleResponse> disable(Authentication caller, @PathVariable UUID ruleId) {
		return ApiResponse.success(AlertRuleResponse.from(api.disable(caller, ruleId)));
	}
}
