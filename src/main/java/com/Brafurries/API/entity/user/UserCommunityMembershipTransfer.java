package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Immutable, tenant-scoped provenance of one user_community_status ownership
 * transfer. Rows are written only inside a verified account consolidation.
 * Schema is owned by BraFurries-Database #49.
 */
@Getter
@Setter
@Entity
@Table(name = "user_community_membership_transfers")
public class UserCommunityMembershipTransfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consolidation_id", nullable = false)
    private UserAccountConsolidation consolidation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_status_id", nullable = false)
    private UserCommunityStatus communityStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
