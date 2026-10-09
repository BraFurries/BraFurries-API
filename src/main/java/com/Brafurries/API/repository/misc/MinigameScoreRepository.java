package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.MinigameScore;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MinigameScoreRepository extends JpaRepository<MinigameScore, Long> {
    List<MinigameScore> findByUserId(Integer userId);
}
