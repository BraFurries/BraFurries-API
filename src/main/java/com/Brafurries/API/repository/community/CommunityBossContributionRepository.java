package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityBossContribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityBossContributionRepository extends JpaRepository<CommunityBossContribution, Integer> {
}
