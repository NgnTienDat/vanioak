package com.h.vanioak.modules.identity.internal.user;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserFacade {

	private final UserRepository repository;
	private final PasswordEncoder passwordEncoder;

	@Override
	public UserPage list(Status status, String cursor, int limit) {
		if (limit < 1 || limit > 100)
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		UUID after = null;
		if (cursor != null) {
			try {
				String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				after = UUID.fromString(decoded);
				if (!after.toString().equals(decoded))
					throw new IllegalArgumentException();
			} catch (IllegalArgumentException ex) {
				throw new IdentityException(ErrorCode.INVALID_CURSOR);
			}
		}
		var users = repository.findEngineers(status, after, PageRequest.of(0, limit + 1));
		var items = users.stream().limit(limit).map(this::view).toList();
		String nextCursor = users.size() > limit
				? Base64.getUrlEncoder().withoutPadding().encodeToString(
						items.getLast().id().toString().getBytes(StandardCharsets.UTF_8))
				: null;
		return new UserPage(items, nextCursor);
	}

	@Override
	@Transactional
	public UserView create(CreateUser command) {
		validate(command.username(), command.password());
		if (repository.existsByUsername(command.username())) {
			throw new IdentityException(ErrorCode.USERNAME_ALREADY_EXISTS);
		}
		return view(save(new UserEntity(command.username(), hash(command.password()))));
	}

	@Override
	public UserView get(UUID id) {
		return view(engineer(id));
	}

	@Override
	@Transactional
	public UserView update(UUID id, UpdateUser command) {
		UserEntity user = engineer(id);
		if (command.username() == null && command.password() == null && command.status() == null) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
		if (command.username() != null)
			validate(command.username(), null);
		if (command.password() != null && command.password().isEmpty()) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
		if (command.username() != null && !command.username().equals(user.getUsername())
				&& repository.existsByUsername(command.username())) {
			throw new IdentityException(ErrorCode.USERNAME_ALREADY_EXISTS);
		}
		user.update(command.username(), command.password() == null ? null : hash(command.password()), command.status());
		return view(save(user));
	}

	@Override
	@Transactional
	public UserView disable(UUID id) {
		UserEntity user = engineer(id);
		user.update(null, null, Status.DISABLED);
		return view(save(user));
	}

	private UserEntity engineer(UUID id) {
		UserEntity user = repository.findById(id).orElseThrow(() -> new IdentityException(ErrorCode.USER_NOT_FOUND));
		if (!"ENGINEER".equals(user.getRole()))
			throw new IdentityException(ErrorCode.ADMIN_USER_FORBIDDEN);
		return user;
	}

	private void validate(String username, String password) {
		if (username == null || username.isBlank() || username.length() > 100
				|| (password != null && password.isEmpty())) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
	}

	private String hash(String password) {
		if (password == null)
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		try {
			return passwordEncoder.encode(password);
		} catch (IllegalArgumentException ex) {
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		}
	}

	private UserEntity save(UserEntity user) {
		try {
			return repository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "users_username_key".equals(violation.getConstraintName())
						&& "23505".equals(violation.getSQLState())) {
					throw new IdentityException(ErrorCode.USERNAME_ALREADY_EXISTS);
				}
			}
			throw ex;
		}
	}

	private UserView view(UserEntity user) {
		return new UserView(user.getId(), user.getUsername(), user.getRole(), user.getStatus());
	}
}
