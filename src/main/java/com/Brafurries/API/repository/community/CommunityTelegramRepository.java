package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityTelegram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityTelegramRepository extends JpaRepository<CommunityTelegram, Integer> {
}
