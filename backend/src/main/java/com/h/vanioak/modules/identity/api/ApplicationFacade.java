package com.h.vanioak.modules.identity.api;

import java.util.List;
import java.util.UUID;

public interface ApplicationFacade {
	ApplicationView create(CreateApplication command);
	ApplicationPage list(Status status, String cursor, int limit);
	ApplicationView update(UUID id, UpdateApplication command);

	enum Status { ACTIVE, DISABLED }
	enum EnvironmentName { DEV, TEST, STAGING }
	record CreateApplication(String name, String description) { }
	record UpdateApplication(String name, String description, Status status) { }
	record EnvironmentView(UUID id, EnvironmentName name, Status status) { }
	record ApplicationView(UUID id, String name, String description, Status status, List<EnvironmentView> environments) { }
	record ApplicationPage(List<ApplicationView> items, String nextCursor) { }
}

