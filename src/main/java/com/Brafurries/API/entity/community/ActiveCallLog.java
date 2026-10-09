package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "active_call_logs")
public class ActiveCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "voice_channel_id", nullable = false)
    private Long voiceChannelId;

    @Column(name = "log_message_id")
    private Long logMessageId;

    @Column(name = "payload_json", columnDefinition = "longtext")
    private String payloadJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
