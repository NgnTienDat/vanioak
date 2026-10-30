package com.h.vanioak.modules.identity.internal.access;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.h.vanioak.modules.identity.api.UserFacade.Status;

public interface UserApplicationAccessRepository extends JpaRepository<UserApplicationAccessEntity, UserApplicationAccessId> {
	boolean existsByUserIdAndApplicationId(UUID userId, UUID applicationId);

	@Query("""
			select u.id as id, u.username as username, u.status as status from UserEntity u
			where u.role = 'ENGINEER'
			and exists (select 1 from UserApplicationAccessEntity ua
				where ua.applicationId = :applicationId and ua.userId = u.id)
			and (:after is null or u.id > :after) order by u.id asc
			""")
	List<AssignedEngineerRow> findAssignedEngineers(@Param("applicationId") UUID applicationId,
			@Param("after") UUID after, Pageable pageable);

	interface AssignedEngineerRow {
		UUID getId();
		String getUsername();
		Status getStatus();
	}

	@Modifying
	@Query(value = "INSERT INTO user_application_access(user_id, application_id) VALUES (:userId, :applicationId) ON CONFLICT (user_id, application_id) DO NOTHING", nativeQuery = true)
	int assign(@Param("userId") UUID userId, @Param("applicationId") UUID applicationId);

	@Modifying
	@Query(value = "DELETE FROM user_application_access WHERE user_id=:userId AND application_id=:applicationId", nativeQuery = true)
	int revoke(@Param("userId") UUID userId, @Param("applicationId") UUID applicationId);
}
