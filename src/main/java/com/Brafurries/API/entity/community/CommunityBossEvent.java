package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_boss_event")
public class CommunityBossEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "boss_name", nullable = false, length = 120)
    private String bossName;

    @Column(name = "max_hp", nullable = false)
    private Integer maxHp;

    @Column(name = "current_hp", nullable = false)
    private Integer currentHp;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "attack_cooldown_seconds", nullable = false)
    private Integer attackCooldownSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
