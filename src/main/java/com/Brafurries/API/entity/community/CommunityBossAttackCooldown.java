package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_boss_attack_cooldown")
public class CommunityBossAttackCooldown {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "cooldown_until", nullable = false)
    private Long cooldownUntil;
}
