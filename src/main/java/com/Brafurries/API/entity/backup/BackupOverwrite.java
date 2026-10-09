package com.Brafurries.API.entity.backup;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "backup_overwrites")
public class BackupOverwrite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private BackupChannel channel;

    @Column(name = "role_name", nullable = false, length = 255)
    private String roleName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private BackupRole role;

    @Column(name = "allow_bits")
    private Long allowBits;

    @Column(name = "deny_bits")
    private Long denyBits;
}
