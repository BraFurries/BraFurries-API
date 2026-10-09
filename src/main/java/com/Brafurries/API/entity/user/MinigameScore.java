package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "UserMinigameScore")
@Table(name = "minigame_scores")
public class MinigameScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "game_name", nullable = false, length = 40)
    private String gameName;

    @Column(nullable = false)
    private Integer points;

    @Column(nullable = false)
    private Boolean won;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
