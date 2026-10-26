package com.h.vanioak.modules.analysis.api;

import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/** Constructed by the authenticated Backend API, never bound from a request. */
public record AuthorizedScope(UUID actorId, Set<UUID> applicationIds, UUID requestedApplicationId, UUID environmentId) {
	public AuthorizedScope {
		Objects.requireNonNull(actorId);
		applicationIds = Set.copyOf(applicationIds);
		if (applicationIds.size() > 1000
				|| requestedApplicationId != null && !applicationIds.contains(requestedApplicationId)
				|| environmentId != null && applicationIds.size() != 1)
			throw new AnalysisException(ErrorCode.INVALID_QUERY);
	}

	public boolean permits(UUID app, UUID env) {
		return applicationIds.contains(app) && (environmentId == null || environmentId.equals(env));
	}
}
