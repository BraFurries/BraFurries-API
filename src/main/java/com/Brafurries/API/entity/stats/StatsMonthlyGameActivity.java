package com.Brafurries.API.entity.stats;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stats_monthly_game_activity")
public class StatsMonthlyGameActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_guild_id", referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(nullable = false, length = 50)
    private String month;

    @Column(name = "game_name", nullable = false, length = 128)
    private String gameName;

    @Column(nullable = false)
    private Integer seconds;

    @Column(name = "last_update", length = 50)
    private String lastUpdate;
}
