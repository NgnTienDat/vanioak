package com.h.vanioak.modules.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Trusted in-process metadata lookup; grants no human access. Includes disabled scopes. */
public interface EnvironmentScopeFacade {
	Optional<UUID> findApplicationId(UUID environmentId);
}
