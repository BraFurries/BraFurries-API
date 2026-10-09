package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_expedition")
public class CommunityExpedition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "started_at", nullable = false)
    private Long startedAt;

    @Column(name = "ends_at", nullable = false)
    private Long endsAt;

    @Column(name = "participants_json", nullable = false, columnDefinition = "longtext")
    private String participantsJson;

    @Column(name = "finalized_at")
    private Long finalizedAt;
}
