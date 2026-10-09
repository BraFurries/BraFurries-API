package com.Brafurries.API.entity.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@IdClass(CommunityUserCapabilityGrantId.class)
@Table(name = "community_user_capability_grants")
public class CommunityUserCapabilityGrant {
    @Id
    @Column(name = "community_id")
    private Integer communityId;

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Id
    @Column(name = "capability", length = 64)
    private String capability;

    @Column(name = "granted_by_user_id", nullable = false)
    private Integer grantedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CommunityUserCapabilityGrant(
        Integer communityId,
        Integer userId,
        String capability,
        Integer grantedByUserId
    ) {
        this.communityId = communityId;
        this.userId = userId;
        this.capability = capability;
        this.grantedByUserId = grantedByUserId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
