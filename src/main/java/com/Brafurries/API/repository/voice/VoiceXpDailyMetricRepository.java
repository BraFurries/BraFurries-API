package com.Brafurries.API.repository.voice;

import com.Brafurries.API.entity.voice.VoiceXpDailyMetric;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoiceXpDailyMetricRepository extends JpaRepository<VoiceXpDailyMetric, Long> {
    List<VoiceXpDailyMetric> findByMetricDayAndServerGuildId(LocalDate metricDay, Long serverGuildId);
}
