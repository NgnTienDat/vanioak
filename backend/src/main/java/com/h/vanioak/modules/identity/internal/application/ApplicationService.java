package com.h.vanioak.modules.identity.internal.application;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApplicationService implements ApplicationFacade {
	private final ApplicationRepository applications;
	private final EnvironmentRepository environments;

	@Override
	@Transactional
	public ApplicationView create(CreateApplication command) {
		if (command.name() == null || command.name().length() > 100)
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		if (applications.existsByName(command.name()))
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		var application = save(new ApplicationEntity(command.name(), command.description()));
		var created = environments.saveAllAndFlush(Arrays.stream(EnvironmentName.values())
				.map(name -> new EnvironmentEntity(application.getId(), name)).toList());
		return view(application, created);
	}

	@Override
	public ApplicationPage list(Status status, String cursor, int limit) {
		if (limit < 1 || limit > 100) throw new IdentityException(ErrorCode.INVALID_REQUEST);
		UUID after = null;
		if (cursor != null) {
			try {
				String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				after = UUID.fromString(decoded);
				if (!after.toString().equals(decoded)) throw new IllegalArgumentException();
			} catch (IllegalArgumentException ex) {
				throw new IdentityException(ErrorCode.INVALID_CURSOR);
			}
		}
		var page = applications.findApplications(status, after, PageRequest.of(0, limit + 1));
		var selected = page.stream().limit(limit).toList();
		var rows = selected.isEmpty() ? List.<EnvironmentEntity>of()
				: environments.findByApplicationIdIn(selected.stream().map(ApplicationEntity::getId).toList());
		var items = selected.stream().map(application -> view(application, rows.stream()
				.filter(environment -> environment.getApplicationId().equals(application.getId())).toList())).toList();
		String next = page.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(
				items.getLast().id().toString().getBytes(StandardCharsets.UTF_8)) : null;
		return new ApplicationPage(items, next);
	}

	@Override
	@Transactional
	public ApplicationView update(UUID id, UpdateApplication command) {
		var application = applications.findById(id)
				.orElseThrow(() -> new IdentityException(ErrorCode.APPLICATION_NOT_FOUND));
		if (command.name() != null && command.name().length() > 100)
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		if (application.getStatus() == Status.DISABLED && (command.name() != null || command.description() != null))
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		if (command.name() != null && !command.name().equals(application.getName())
				&& applications.existsByName(command.name()))
			throw new IdentityException(ErrorCode.INVALID_REQUEST);
		application.update(command.name(), command.description(), command.status());
		return view(save(application), environments.findByApplicationIdIn(List.of(id)));
	}

	private ApplicationEntity save(ApplicationEntity application) {
		try {
			return applications.saveAndFlush(application);
		} catch (DataIntegrityViolationException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "applications_name_key".equals(violation.getConstraintName())
						&& "23505".equals(violation.getSQLState()))
					throw new IdentityException(ErrorCode.INVALID_REQUEST);
			}
			throw ex;
		}
	}

	private ApplicationView view(ApplicationEntity application, List<EnvironmentEntity> rows) {
		var summaries = rows.stream().sorted(Comparator.comparing(EnvironmentEntity::getName))
				.map(row -> new EnvironmentView(row.getId(), row.getName(), row.getStatus())).toList();
		return new ApplicationView(application.getId(), application.getName(), application.getDescription(),
				application.getStatus(), summaries);
	}
}

