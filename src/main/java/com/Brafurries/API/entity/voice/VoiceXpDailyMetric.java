package com.Brafurries.API.entity.voice;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "voice_xp_daily_metrics")
public class VoiceXpDailyMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_day", nullable = false)
    private LocalDate metricDay;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(nullable = false, length = 32)
    private String modality;

    @Column(name = "voice_channel_id")
    private Long voiceChannelId;

    @Column(name = "total_xp", nullable = false)
    private Long totalXp;

    @Column(name = "grants_count", nullable = false)
    private Long grantsCount;

    @Column(name = "blocks_count", nullable = false)
    private Long blocksCount;

    @Column(name = "eligible_seconds", nullable = false)
    private Long eligibleSeconds;

    @Column(name = "ineligible_seconds", nullable = false)
    private Long ineligibleSeconds;
}
