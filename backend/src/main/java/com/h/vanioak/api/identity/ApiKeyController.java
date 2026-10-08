package com.h.vanioak.api.identity;

import java.security.Principal;
import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.identity.dto.ApiKeyListResponse;
import com.h.vanioak.api.identity.dto.ApiKeyResponse;
import com.h.vanioak.api.identity.dto.ApiKeySecretResponse;
import com.h.vanioak.api.identity.dto.CreateApiKeyRequest;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.CredentialView;
import com.h.vanioak.modules.identity.api.ApiKeyFacade.IssuedKey;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ApiKeyController {
	private final ApiKeyFacade keys;

	@GetMapping("/api/v1/applications/{applicationId}/environments/{environmentId}/api-keys")
	public ApiResponse<ApiKeyListResponse> list(@PathVariable UUID applicationId, @PathVariable UUID environmentId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		var page = keys.list(applicationId, environmentId, cursor, limit);
		return ApiResponse
				.success(new ApiKeyListResponse(page.items().stream().map(this::response).toList(), page.nextCursor()));
	}

	@PostMapping("/api/v1/applications/{applicationId}/environments/{environmentId}/api-keys")
	public ResponseEntity<ApiResponse<ApiKeySecretResponse>> create(@PathVariable UUID applicationId,
			@PathVariable UUID environmentId, Principal principal,
			@Valid @RequestBody(required = false) CreateApiKeyRequest request) {
		var issued = keys.create(applicationId, environmentId, UUID.fromString(principal.getName()),
				request == null ? null : request.expiresAt());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(secret(issued)));
	}

	@PostMapping("/api/v1/api-keys/{credentialId}/rotate")
	public ApiResponse<ApiKeySecretResponse> rotate(@PathVariable UUID credentialId, Principal principal) {
		return ApiResponse.success(secret(keys.rotate(credentialId, UUID.fromString(principal.getName()))));
	}

	@DeleteMapping("/api/v1/api-keys/{credentialId}")
	public ApiResponse<ApiKeyResponse> revoke(@PathVariable UUID credentialId) {
		return ApiResponse.success(response(keys.revoke(credentialId)));
	}

	private ApiKeySecretResponse secret(IssuedKey issued) {
		return new ApiKeySecretResponse(response(issued.credential()), issued.apiKey());
	}

	private ApiKeyResponse response(CredentialView credential) {
		return new ApiKeyResponse(credential.id(), credential.environmentId(), credential.keyPrefix(),
				credential.status(),
				credential.expiresAt(), credential.createdAt(), credential.revokedAt());
	}
}
