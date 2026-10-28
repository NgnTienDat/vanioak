package com.h.vanioak.modules.identity.internal.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.h.vanioak.modules.identity.api.EnvironmentScopeFacade;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
public class EnvironmentScopeService implements EnvironmentScopeFacade {
	private final EnvironmentRepository environments;
	@Override public Optional<UUID> findApplicationId(UUID environmentId) {
		return environments.findById(Objects.requireNonNull(environmentId)).map(EnvironmentEntity::getApplicationId);
	}
}
