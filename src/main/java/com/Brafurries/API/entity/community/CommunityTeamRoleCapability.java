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
@IdClass(CommunityTeamRoleCapabilityId.class)
@Table(name = "community_team_role_capabilities")
public class CommunityTeamRoleCapability {
    @Id
    @Column(name = "community_id")
    private Integer communityId;

    @Id
    @Column(name = "role_id")
    private Integer roleId;

    @Id
    @Column(name = "capability", length = 64)
    private String capability;

    @Column(name = "granted_by_user_id", nullable = false)
    private Integer grantedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CommunityTeamRoleCapability(
        Integer communityId,
        Integer roleId,
        String capability,
        Integer grantedByUserId
    ) {
        this.communityId = communityId;
        this.roleId = roleId;
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
