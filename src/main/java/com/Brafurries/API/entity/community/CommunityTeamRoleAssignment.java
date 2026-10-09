package com.Brafurries.API.entity.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@IdClass(CommunityTeamRoleAssignmentId.class)
@Table(name = "community_team_role_assignments")
public class CommunityTeamRoleAssignment {
    @Id
    @Column(name = "community_id")
    private Integer communityId;

    @Id
    @Column(name = "role_id")
    private Integer roleId;

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CommunityTeamRoleAssignment(Integer communityId, Integer roleId, Integer userId) {
        this.communityId = communityId;
        this.roleId = roleId;
        this.userId = userId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
