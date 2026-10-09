package com.Brafurries.API.entity.community;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class CommunityTeamRoleCapabilityId implements Serializable {
    private Integer communityId;
    private Integer roleId;
    private String capability;

    public CommunityTeamRoleCapabilityId(Integer communityId, Integer roleId, String capability) {
        this.communityId = communityId;
        this.roleId = roleId;
        this.capability = capability;
    }
}
