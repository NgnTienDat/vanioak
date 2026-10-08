package com.h.vanioak.modules.identity.api;

import java.util.List;
import java.util.UUID;

public interface UserFacade {

	UserPage list(Status status, String cursor, int limit);
	UserView create(CreateUser command);
	UserView get(UUID id);
	UserView update(UUID id, UpdateUser command);
	UserView disable(UUID id);

	enum Status { ACTIVE, DISABLED }

	record CreateUser(String username, String password) { }
	record UpdateUser(String username, String password, Status status) { }
	record UserView(UUID id, String username, String role, Status status) { }
	record UserPage(List<UserView> items, String nextCursor) { }
}
