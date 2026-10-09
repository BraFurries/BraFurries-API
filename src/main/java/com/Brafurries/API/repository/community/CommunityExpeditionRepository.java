package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityExpedition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityExpeditionRepository extends JpaRepository<CommunityExpedition, Integer> {
}
