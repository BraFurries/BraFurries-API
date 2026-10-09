package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignmentId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTeamRoleAssignmentRepository
    extends JpaRepository<CommunityTeamRoleAssignment, CommunityTeamRoleAssignmentId> {

    List<CommunityTeamRoleAssignment> findByCommunityIdAndRoleIdOrderByUserIdAsc(
        Integer communityId,
        Integer roleId
    );

    List<CommunityTeamRoleAssignment> findByCommunityIdAndUserIdOrderByRoleIdAsc(
        Integer communityId,
        Integer userId
    );

    List<CommunityTeamRoleAssignment> findByCommunityIdAndUserIdInOrderByUserIdAscRoleIdAsc(
        Integer communityId,
        Collection<Integer> userIds
    );

    Optional<CommunityTeamRoleAssignment> findByCommunityIdAndRoleIdAndUserId(
        Integer communityId,
        Integer roleId,
        Integer userId
    );

    long countByCommunityIdAndRoleId(Integer communityId, Integer roleId);

    @Modifying
    @Query(
        value = """
            INSERT INTO community_team_role_assignments
                (community_id, role_id, user_id)
            VALUES (:communityId, :roleId, :userId)
            ON DUPLICATE KEY UPDATE created_at = created_at
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("communityId") Integer communityId,
        @Param("roleId") Integer roleId,
        @Param("userId") Integer userId
    );

    long deleteByCommunityIdAndRoleIdAndUserId(
        Integer communityId,
        Integer roleId,
        Integer userId
    );
}
