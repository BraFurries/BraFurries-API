package com.Brafurries.API.entity.portaria;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "portaria_account_release")
public class PortariaAccountRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "access_mode", nullable = false, length = 20)
    private String accessMode;

    @Column(name = "requires_form", nullable = false)
    private Boolean requiresForm;

    @Column(name = "released_by")
    private Long releasedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
