package com.h.vanioak.modules.identity.api;

import java.util.UUID;

public interface ApplicationAccessFacade {
	Assignment assign(UUID actorId, UUID applicationId, UUID userId);
	Assignment revoke(UUID actorId, UUID applicationId, UUID userId);
	void requireApplicationReadAccess(UUID actorId, UUID applicationId);
	void requireEnvironmentReadAccess(UUID actorId, UUID applicationId, UUID environmentId);

	record Assignment(UUID userId, UUID applicationId) { }
}
