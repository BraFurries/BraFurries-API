package com.Brafurries.API.repository.stats;

import com.Brafurries.API.entity.stats.StatsWeeklyGameActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatsWeeklyGameActivityRepository extends JpaRepository<StatsWeeklyGameActivity, Integer> {
}
