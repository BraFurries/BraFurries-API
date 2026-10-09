package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "discord_server_messages")
public class DiscordServerMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(length = 1024)
    private String birthday;

    @Column(length = 1024)
    private String bump;
}
