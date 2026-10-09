package com.Brafurries.API.entity.stats;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stats_weekly_game_activity")
public class StatsWeeklyGameActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(nullable = false, length = 50)
    private String week;

    @Column(name = "game_name", nullable = false, length = 128)
    private String gameName;

    @Column(nullable = false)
    private Integer seconds;

    @Column(name = "last_update", length = 50)
    private String lastUpdate;
}
