package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "community_network_member_status",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cnms_network_user",
        columnNames = {"network_type", "external_network_id", "user_id"}
    )
)
public class CommunityNetworkMemberStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(name = "network_type", nullable = false, length = 32)
    private String networkType;

    @Column(name = "external_network_id", nullable = false)
    private Long externalNetworkId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "is_present", nullable = false)
    private Boolean isPresent;

    @Column(name = "first_known_join_at")
    private LocalDateTime firstKnownJoinAt;

    @Column(name = "first_known_join_source", length = 32)
    private String firstKnownJoinSource;

    @Column(name = "last_join_at")
    private LocalDateTime lastJoinAt;

    @Column(name = "last_join_source", length = 32)
    private String lastJoinSource;

    @Column(name = "current_presence_since")
    private LocalDateTime currentPresenceSince;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "last_observed_at", nullable = false)
    private LocalDateTime lastObservedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_sync_run_id")
    private CommunityNetworkSyncRun lastSyncRun;

    @Column(name = "approval_required")
    private Boolean approvalRequired;

    @Column
    private Boolean approved;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
