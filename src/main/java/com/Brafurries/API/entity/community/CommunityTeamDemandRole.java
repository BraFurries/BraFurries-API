package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@IdClass(CommunityTeamDemandRoleId.class)
@Table(name = "community_team_demand_roles")
public class CommunityTeamDemandRole {
    @Id
    @Column(name = "community_id")
    private Integer communityId;

    @Id
    @Column(name = "demand_id")
    private Integer demandId;

    @Id
    @Column(name = "role_id")
    private Integer roleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CommunityTeamDemandRole(Integer communityId, Integer demandId, Integer roleId) {
        this.communityId = communityId;
        this.demandId = demandId;
        this.roleId = roleId;
    }

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
