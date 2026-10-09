package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_discord")
public class UserDiscord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "discord_user_id", nullable = false, unique = true)
    private Long discordUserId;

    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "display_name", length = 32)
    private String displayName;
}
