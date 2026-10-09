package com.Brafurries.API.entity.backup;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "backup_server_settings", uniqueConstraints = {
        @UniqueConstraint(name = "uq_backup_server_settings_guild", columnNames = "guild_id")
})
public class BackupServerSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "max_normal_backups", nullable = false)
    private Integer maxNormalBackups;

    @Column(name = "max_periodic_backups", nullable = false)
    private Integer maxPeriodicBackups;

    @Column(name = "periodic_backups_enabled", nullable = false)
    private Boolean periodicBackupsEnabled;

    @Column(name = "periodicity_minutes", nullable = false)
    private Integer periodicityMinutes;

    @Column(name = "periodicity_frequency", nullable = false, length = 10)
    private String periodicityFrequency;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
