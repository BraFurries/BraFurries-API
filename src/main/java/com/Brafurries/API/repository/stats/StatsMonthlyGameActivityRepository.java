package com.Brafurries.API.repository.stats;

import com.Brafurries.API.entity.stats.StatsMonthlyGameActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatsMonthlyGameActivityRepository extends JpaRepository<StatsMonthlyGameActivity, Integer> {
}
