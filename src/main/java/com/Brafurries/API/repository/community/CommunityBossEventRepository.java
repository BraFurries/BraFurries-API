package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityBossEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityBossEventRepository extends JpaRepository<CommunityBossEvent, Integer> {
}
