package com.h.vanioak.modules.identity.internal.access;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserApplicationAccessRepository extends JpaRepository<UserApplicationAccessEntity, UserApplicationAccessId> {
	boolean existsByUserIdAndApplicationId(UUID userId, UUID applicationId);

	@Modifying
	@Query(value = "INSERT INTO user_application_access(user_id, application_id) VALUES (:userId, :applicationId) ON CONFLICT (user_id, application_id) DO NOTHING", nativeQuery = true)
	int assign(@Param("userId") UUID userId, @Param("applicationId") UUID applicationId);

	@Modifying
	@Query(value = "DELETE FROM user_application_access WHERE user_id=:userId AND application_id=:applicationId", nativeQuery = true)
	int revoke(@Param("userId") UUID userId, @Param("applicationId") UUID applicationId);
}
