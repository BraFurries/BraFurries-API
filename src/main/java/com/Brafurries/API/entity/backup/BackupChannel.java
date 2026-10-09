package com.Brafurries.API.entity.backup;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "backup_channels")
public class BackupChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "backup_id", nullable = false)
    private BackupDiscord backup;

    @Column(name = "discord_id", nullable = false)
    private Long discordId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private Integer type;

    @Column(name = "position")
    private Integer position;

    @Column(columnDefinition = "text")
    private String topic;

    @Column(name = "nsfw")
    private Boolean nsfw;

    @OneToMany(mappedBy = "channel", fetch = FetchType.LAZY)
    private List<BackupOverwrite> overwrites = new ArrayList<>();
}
