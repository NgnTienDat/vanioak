package com.h.vanioak.api.identity;

import java.util.UUID;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;
import com.h.vanioak.api.identity.dto.CreateUserRequest;
import com.h.vanioak.api.identity.dto.UpdateUserRequest;
import com.h.vanioak.api.identity.dto.UserListResponse;
import com.h.vanioak.api.identity.dto.UserResponse;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.api.UserFacade.CreateUser;
import com.h.vanioak.modules.identity.api.UserFacade.Status;
import com.h.vanioak.modules.identity.api.UserFacade.UpdateUser;
import com.h.vanioak.modules.identity.api.UserFacade.UserView;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor 
public class UserController {

	private final UserFacade users;

	@GetMapping
	public ApiResponse<UserListResponse> list(@RequestParam(required = false) Status status,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		var page = users.list(status, cursor, limit);
		return ApiResponse.success(new UserListResponse(page.items().stream().map(this::response).toList(), page.nextCursor()));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
				response(users.create(new CreateUser(request.username(), request.password())))));
	}

	@GetMapping("/{userId}")
	public ApiResponse<UserResponse> get(@PathVariable String userId) {
		return ApiResponse.success(response(users.get(id(userId))));
	}

	@PatchMapping("/{userId}")
	public ApiResponse<UserResponse> update(@PathVariable String userId, @Valid @RequestBody UpdateUserRequest request) {
		return ApiResponse.success(response(users.update(id(userId),
				new UpdateUser(request.getUsername(), request.getPassword(), request.getStatus()))));
	}

	@DeleteMapping("/{userId}")
	public ApiResponse<UserResponse> disable(@PathVariable String userId) {
		return ApiResponse.success(response(users.disable(id(userId))));
	}

	private UUID id(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException ex) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
	}

	private UserResponse response(UserView user) {
		return new UserResponse(user.id(), user.username(), user.role(), user.status());
	}
}
