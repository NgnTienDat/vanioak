package com.h.vanioak.modules.identity.internal.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "vanioak.identity.bootstrap-admin", name = "enabled", havingValue = "true")
public class InitialAdminBootstrap implements ApplicationRunner {

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final String username;
	private final String password;

	public InitialAdminBootstrap(UserRepository users, PasswordEncoder encoder,
			@Value("${vanioak.identity.bootstrap-admin.username:}") String username,
			@Value("${vanioak.identity.bootstrap-admin.password:}") String password) {
		this.users = users;
		this.encoder = encoder;
		this.username = username;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments arguments) {
		if (username == null || username.isBlank() || username.length() > 100) {
			throw new IllegalStateException("Invalid ADMIN bootstrap username configuration");
		}
		var existing = users.findByUsername(username);
		if (existing.isPresent()) {
			if (!"ADMIN".equals(existing.get().getRole())) {
				throw new IllegalStateException("ADMIN bootstrap username belongs to another account role");
			}
			return;
		}
		if (password == null || password.isEmpty()) {
			throw new IllegalStateException("Invalid ADMIN bootstrap password configuration");
		}
		String hash;
		try {
			hash = encoder.encode(password);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Invalid ADMIN bootstrap password configuration");
		}
		users.saveAndFlush(UserEntity.initialAdmin(username, hash));
	}
}
