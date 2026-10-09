package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_logs")
public class ConfigLogs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "log_channel")
    private Long logChannel;
}
