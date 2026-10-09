package com.Brafurries.API.entity.backup;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "backup_discord", indexes = {
        @Index(name = "idx_backup_discord_guild_type", columnList = "guild_id, backup_type, id")
})
public class BackupDiscord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "backup_type", nullable = false, length = 20)
    private String backupType;

    @Column(name = "created_by_discord_user_id")
    private Long createdByDiscordUserId;

    @Column(name = "idempotency_key", length = 96)
    private String idempotencyKey;

    @Column(name = "creation_audited_at")
    private Instant creationAuditedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "backup", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BackupChannel> channels = new ArrayList<>();

    @OneToMany(mappedBy = "backup", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BackupRole> roles = new ArrayList<>();
}
