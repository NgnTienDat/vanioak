package com.h.vanioak.modules.alert.internal.engine;

import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class AlertScopeLock {
	private final EntityManager entityManager;

	public void event(UUID id) { acquire(0x56414C45, id.hashCode()); }
	public void scope(UUID env, String fingerprint) { acquire(0x56414C53, Objects.hash(env, fingerprint)); }

	private void acquire(int namespace, int key) {
		if (!TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("Alert lock requires transaction");
		// shortcut: hash collisions serialize unrelated scopes; expand keys if contention is measured.
		entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:namespace, :key)")
				.setParameter("namespace", namespace).setParameter("key", key).getSingleResult();
	}
}
