package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_records")
public class UserRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "voice_time")
    private Integer voiceTime;

    @Column(name = "game_time")
    private Integer gameTime;

    @Column(name = "game_name", length = 50)
    private String gameName;

    @Column(name = "bumps")
    private Integer bumps;

    @Column(name = "boops")
    private Integer boops;

    @Column(name = "boops_received")
    private Integer boopsReceived;
}
