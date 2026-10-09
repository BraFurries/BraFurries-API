package com.Brafurries.API.entity.backup;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "backup_roles")
public class BackupRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "backup_id", nullable = false)
    private BackupDiscord backup;

    @Column(name = "discord_id", nullable = false)
    private Long discordId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "color")
    private Integer color;

    @Column(name = "permissions")
    private Long permissions;

    @Column(name = "position")
    private Integer position;

    @Column(name = "hoist")
    private Boolean hoist;

    @Column(name = "mentionable")
    private Boolean mentionable;

    @OneToMany(mappedBy = "role", fetch = FetchType.LAZY)
    private List<BackupOverwrite> overwrites = new ArrayList<>();
}
