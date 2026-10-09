package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_economy", uniqueConstraints = {
        @UniqueConstraint(name = "server_guild_id", columnNames = "server_guild_id")
})
public class ConfigEconomy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(name = "currency_name", nullable = false, length = 16)
    private String currencyName;

    @Column(name = "currency_prefix", nullable = false, length = 16)
    private String currencyPrefix;

    @Column(name = "message_points_per_day")
    private Integer messagePointsPerDay;

    @Column(name = "message_points")
    private Integer messagePoints;

    @Column(name = "daily_max_points")
    private Integer dailyMaxPoints;

    @Column(name = "birthday_points")
    private Integer birthdayPoints;

    @Column(name = "bump_points")
    private Integer bumpPoints;

    @Column(name = "transfer_tax")
    private Double transferTax;

    @Column(name = "bet_odds_3x")
    private Double betOdds3x;

    @Column(name = "bet_odds_2x")
    private Double betOdds2x;

    @Column(name = "bet_odds_1x")
    private Double betOdds1x;

    @Column(name = "bump_reward_enabled", nullable = false)
    private Boolean bumpRewardEnabled;
}
