package com.Brafurries.API.entity.voice;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "voice_sessions")
@IdClass(VoiceSessionId.class)
public class VoiceSession {
    @Id
    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Id
    @Column(name = "discord_user_id", nullable = false)
    private Long discordUserId;

    @Column(name = "voice_channel_id", nullable = false)
    private Long voiceChannelId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_tick_at", nullable = false)
    private LocalDateTime lastTickAt;

    @Column(name = "is_eligible", nullable = false)
    private Boolean isEligible;

    @Column(name = "is_self_muted", nullable = false)
    private Boolean isSelfMuted;

    @Column(name = "is_self_deafened", nullable = false)
    private Boolean isSelfDeafened;

    @Column(name = "is_server_muted", nullable = false)
    private Boolean isServerMuted;

    @Column(name = "is_server_deafened", nullable = false)
    private Boolean isServerDeafened;
}
