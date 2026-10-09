package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTeamRoleRepository extends JpaRepository<CommunityTeamRole, Integer> {
    List<CommunityTeamRole> findByCommunityOrderByNameAsc(Community community);
    Optional<CommunityTeamRole> findByIdAndCommunity(Integer id, Community community);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT role
        FROM CommunityTeamRole role
        WHERE role.id = :id
          AND role.community = :community
        """)
    Optional<CommunityTeamRole> findForAssignmentByIdAndCommunity(
        @Param("id") Integer id,
        @Param("community") Community community
    );
    List<CommunityTeamRole> findByCommunityAndIdInOrderByNameAsc(Community community, Collection<Integer> ids);
    long countByParentRole(CommunityTeamRole parentRole);
}
