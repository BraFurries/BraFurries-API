package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.MinigameScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository("userMinigameScoreRepository")
public interface MinigameScoreRepository extends JpaRepository<MinigameScore, Long> {
}
