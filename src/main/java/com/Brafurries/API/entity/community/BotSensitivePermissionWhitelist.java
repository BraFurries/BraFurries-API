package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "bot_sensitive_permission_whitelist")
public class BotSensitivePermissionWhitelist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_type", nullable = false, length = 10)
    private String actorType;

    @Column(name = "permission_name", nullable = false, length = 100)
    private String permissionName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
