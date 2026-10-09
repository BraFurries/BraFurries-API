package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityNetworkMemberEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityNetworkMemberEventRepository
    extends JpaRepository<CommunityNetworkMemberEvent, Long> {
}
