package com.Brafurries.API.entity.misc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity(name = "MiscMinigameScore")
@Table(name = "minigame_scores")
public class MinigameScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "game_name", nullable = false, length = 40)
    private String gameName;

    @Column(name = "points", nullable = false)
    private Integer points;

    @Column(name = "won", nullable = false)
    private Boolean won;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
