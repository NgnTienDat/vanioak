package com.h.vanioak.api.identity;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.identity.dto.ApplicationListResponse;
import com.h.vanioak.api.identity.dto.ApplicationResponse;
import com.h.vanioak.api.identity.dto.ApplicationResponse.EnvironmentSummary;
import com.h.vanioak.api.identity.dto.CreateApplicationRequest;
import com.h.vanioak.api.identity.dto.UpdateApplicationRequest;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade.ApplicationView;
import com.h.vanioak.modules.identity.api.ApplicationFacade.CreateApplication;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import com.h.vanioak.modules.identity.api.ApplicationFacade.UpdateApplication;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/applications")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ApplicationController {
	private final ApplicationFacade applications;

	@PostMapping
	public ResponseEntity<ApiResponse<ApplicationResponse>> create(@Valid @RequestBody CreateApplicationRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response(
				applications.create(new CreateApplication(request.getName(), request.getDescription())))));
	}

	@GetMapping
	public ApiResponse<ApplicationListResponse> list(@RequestParam(required = false) Status status,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		var page = applications.list(status, cursor, limit);
		return ApiResponse.success(new ApplicationListResponse(page.items().stream().map(this::response).toList(), page.nextCursor()));
	}

	@PatchMapping("/{applicationId}")
	public ApiResponse<ApplicationResponse> update(@PathVariable UUID applicationId,
			@Valid @RequestBody UpdateApplicationRequest request) {
		return ApiResponse.success(response(applications.update(applicationId,
				new UpdateApplication(request.getName(), request.getDescription(), request.getStatus()))));
	}

	private ApplicationResponse response(ApplicationView application) {
		return new ApplicationResponse(application.id(), application.name(), application.description(), application.status(),
				application.environments().stream().map(row -> new EnvironmentSummary(row.id(), row.name(), row.status())).toList());
	}
}

