package com.h.vanioak.modules.alert.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IncidentResolutionFacade {

	Optional<Resolution> resolve(IncidentReadFacade.Scope scope, UUID incidentId);

	enum Source { MANUAL, AUTOMATIC }

	record Resolution(UUID id, IncidentReadFacade.Status status, Instant resolvedAt,
			Source resolutionSource, UUID resolvedByUserId) { }
}
