package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "blacklisted_games")
public class BlacklistedGame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(name = "game_name", nullable = false, length = 50)
    private String gameName;
}
