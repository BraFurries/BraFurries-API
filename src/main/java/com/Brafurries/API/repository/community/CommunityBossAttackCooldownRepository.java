package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityBossAttackCooldown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityBossAttackCooldownRepository extends JpaRepository<CommunityBossAttackCooldown, Integer> {
}
