package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_warnings_settings")
public class ConfigWarningsSettings {

    @Id
    @Column(name = "community_id")
    private Integer communityId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(name = "warnings_expire", nullable = false)
    private Boolean warningsExpire;

    @Column(name = "mute_on_warn", nullable = false)
    private Boolean muteOnWarn;

    @Column(name = "warnings_limit", nullable = false)
    private Integer warningsLimit;

    @Column(name = "expiration_time")
    private Integer expirationTime;
}
