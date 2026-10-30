package com.h.vanioak.modules.identity.api;

import java.util.List;
import java.util.UUID;

public interface ApplicationAccessFacade {
	AssignedEngineerPage listEngineers(UUID actorId, UUID applicationId, String cursor, int limit);
	Assignment assign(UUID actorId, UUID applicationId, UUID userId);
	Assignment revoke(UUID actorId, UUID applicationId, UUID userId);
	void requireApplicationReadAccess(UUID actorId, UUID applicationId);
	void requireEnvironmentReadAccess(UUID actorId, UUID applicationId, UUID environmentId);

	record Assignment(UUID userId, UUID applicationId) { }
	record AssignedEngineer(UUID id, String username, UserFacade.Status status) { }
	record AssignedEngineerPage(List<AssignedEngineer> items, String nextCursor) { }
}
