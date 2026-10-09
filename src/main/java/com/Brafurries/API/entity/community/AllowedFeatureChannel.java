package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "allowed_feature_channels")
public class AllowedFeatureChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "feature_key", nullable = false, length = 100)
    private String featureKey;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
