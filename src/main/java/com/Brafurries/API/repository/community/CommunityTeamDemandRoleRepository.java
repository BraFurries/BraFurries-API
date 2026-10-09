package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityTeamDemandRole;
import com.Brafurries.API.entity.community.CommunityTeamDemandRoleId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTeamDemandRoleRepository extends JpaRepository<CommunityTeamDemandRole, CommunityTeamDemandRoleId> {
    List<CommunityTeamDemandRole> findByCommunityIdAndDemandIdOrderByRoleIdAsc(Integer communityId, Integer demandId);
    long countByCommunityIdAndRoleId(Integer communityId, Integer roleId);
    long deleteByCommunityIdAndDemandId(Integer communityId, Integer demandId);
    long deleteByCommunityIdAndRoleId(Integer communityId, Integer roleId);
}
