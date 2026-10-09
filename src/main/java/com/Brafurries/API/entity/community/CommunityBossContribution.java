package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_boss_contribution")
public class CommunityBossContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_damage", nullable = false)
    private Long totalDamage;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
