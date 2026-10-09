package com.Brafurries.API.entity.community;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class CommunityTeamRoleAssignmentId implements Serializable {
    private Integer communityId;
    private Integer roleId;
    private Integer userId;

    public CommunityTeamRoleAssignmentId(Integer communityId, Integer roleId, Integer userId) {
        this.communityId = communityId;
        this.roleId = roleId;
        this.userId = userId;
    }
}
