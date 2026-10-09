package com.Brafurries.API.entity.community;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class CommunityTeamDemandRoleId implements Serializable {
    private Integer communityId;
    private Integer demandId;
    private Integer roleId;

    public CommunityTeamDemandRoleId(Integer communityId, Integer demandId, Integer roleId) {
        this.communityId = communityId;
        this.demandId = demandId;
        this.roleId = roleId;
    }
}
