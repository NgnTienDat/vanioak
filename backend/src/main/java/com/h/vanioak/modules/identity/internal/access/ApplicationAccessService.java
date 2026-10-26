package com.h.vanioak.modules.identity.internal.access;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.internal.application.ApplicationRepository;
import com.h.vanioak.modules.identity.internal.application.EnvironmentRepository;
import com.h.vanioak.modules.identity.internal.user.UserEntity;
import com.h.vanioak.modules.identity.internal.user.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApplicationAccessService implements ApplicationAccessFacade {
	private final UserRepository users;
	private final ApplicationRepository applications;
	private final EnvironmentRepository environments;
	private final UserApplicationAccessRepository access;

	public UserEntity currentUser(UUID actorId) {
		if (actorId == null) throw new IdentityException(ErrorCode.INVALID_CREDENTIALS);
		var actor = users.findById(actorId).orElseThrow(() -> new IdentityException(ErrorCode.INVALID_CREDENTIALS));
		if (actor.getStatus() != UserFacade.Status.ACTIVE
				|| !("ADMIN".equals(actor.getRole()) || "ENGINEER".equals(actor.getRole())))
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		return actor;
	}

	private void requireAdmin(UUID actorId) {
		if (!"ADMIN".equals(currentUser(actorId).getRole())) throw new IdentityException(ErrorCode.ACCESS_DENIED);
	}

	@Override
	@Transactional
	public Assignment assign(UUID actorId, UUID applicationId, UUID userId) {
		requireAdmin(actorId);
		var application = applications.findById(applicationId)
				.orElseThrow(() -> new IdentityException(ErrorCode.APPLICATION_NOT_FOUND));
		var target = engineer(userId);
		if (application.getStatus() != ApplicationFacade.Status.ACTIVE || target.getStatus() != UserFacade.Status.ACTIVE)
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		access.assign(userId, applicationId);
		return new Assignment(userId, applicationId);
	}

	@Override
	@Transactional
	public Assignment revoke(UUID actorId, UUID applicationId, UUID userId) {
		requireAdmin(actorId);
		if (!applications.existsById(applicationId)) throw new IdentityException(ErrorCode.APPLICATION_NOT_FOUND);
		engineer(userId);
		access.revoke(userId, applicationId);
		return new Assignment(userId, applicationId);
	}

	private UserEntity engineer(UUID userId) {
		var target = users.findById(userId).orElseThrow(() -> new IdentityException(ErrorCode.USER_NOT_FOUND));
		if (!"ENGINEER".equals(target.getRole())) throw new IdentityException(ErrorCode.ADMIN_USER_FORBIDDEN);
		return target;
	}

	@Override
	public void requireApplicationReadAccess(UUID actorId, UUID applicationId) {
		var actor = currentUser(actorId);
		if (!"ADMIN".equals(actor.getRole()) && !access.existsByUserIdAndApplicationId(actorId, applicationId))
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		if (!applications.existsById(applicationId)) throw new IdentityException(ErrorCode.APPLICATION_NOT_FOUND);
	}

	@Override
	public void requireEnvironmentReadAccess(UUID actorId, UUID applicationId, UUID environmentId) {
		requireApplicationReadAccess(actorId, applicationId);
		var environment = environments.findById(environmentId);
		if (environment.isEmpty()) {
			if ("ADMIN".equals(currentUser(actorId).getRole())) throw new IdentityException(ErrorCode.ENVIRONMENT_NOT_FOUND);
			throw new IdentityException(ErrorCode.ACCESS_DENIED);
		}
		if (!applicationId.equals(environment.get().getApplicationId())) throw new IdentityException(ErrorCode.ACCESS_DENIED);
	}
}
