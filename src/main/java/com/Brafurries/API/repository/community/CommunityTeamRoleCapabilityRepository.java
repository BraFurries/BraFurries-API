package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityTeamRoleCapability;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapabilityId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTeamRoleCapabilityRepository
    extends JpaRepository<CommunityTeamRoleCapability, CommunityTeamRoleCapabilityId> {

    List<CommunityTeamRoleCapability> findByCommunityIdAndRoleIdIn(
        Integer communityId,
        Collection<Integer> roleIds
    );

    List<CommunityTeamRoleCapability> findByCommunityIdAndRoleIdOrderByCapabilityAsc(
        Integer communityId,
        Integer roleId
    );
}
