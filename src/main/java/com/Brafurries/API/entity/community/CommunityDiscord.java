package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_discord")
public class CommunityDiscord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "discord_admin_id")
    private Long discordAdminId;

    @Column(name = "users_quantity", nullable = false)
    private Integer usersQuantity;

    @Column(name = "membership_sync_state", nullable = false, length = 32)
    private String membershipSyncState = "RECONCILIATION_REQUIRED";

    @Column(name = "last_full_reconciliation_at")
    private LocalDateTime lastFullReconciliationAt;

    @Column(name = "last_membership_event_at")
    private LocalDateTime lastMembershipEventAt;
}
