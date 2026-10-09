package com.Brafurries.API.entity.voice;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "voice_xp_audit_log")
public class VoiceXpAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "discord_user_id", nullable = false)
    private Long discordUserId;

    @Column(name = "voice_channel_id")
    private Long voiceChannelId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "xp_amount", nullable = false)
    private Integer xpAmount;

    @Column(name = "eligible_seconds", nullable = false)
    private Integer eligibleSeconds;

    @Column(name = "ineligible_seconds", nullable = false)
    private Integer ineligibleSeconds;

    @Column(name = "meta_json", columnDefinition = "longtext")
    private String metaJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
