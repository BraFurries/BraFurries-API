package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityUserCapabilityGrant;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrantId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityUserCapabilityGrantRepository
    extends JpaRepository<CommunityUserCapabilityGrant, CommunityUserCapabilityGrantId> {

    List<CommunityUserCapabilityGrant> findByCommunityIdAndUserId(
        Integer communityId,
        Integer userId
    );

    List<CommunityUserCapabilityGrant> findByCommunityIdAndUserIdOrderByCapabilityAsc(
        Integer communityId,
        Integer userId
    );
}
