package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamDemand;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTeamDemandRepository extends JpaRepository<CommunityTeamDemand, Integer> {
    List<CommunityTeamDemand> findByCommunityOrderByNameAsc(Community community);
    Optional<CommunityTeamDemand> findByIdAndCommunity(Integer id, Community community);
    List<CommunityTeamDemand> findByCommunityAndIdInOrderByNameAsc(
        Community community,
        Collection<Integer> ids
    );
}
