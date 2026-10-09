package com.Brafurries.API.entity.community;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class CommunityUserCapabilityGrantId implements Serializable {
    private Integer communityId;
    private Integer userId;
    private String capability;

    public CommunityUserCapabilityGrantId(Integer communityId, Integer userId, String capability) {
        this.communityId = communityId;
        this.userId = userId;
        this.capability = capability;
    }
}
