package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_custom_roles")
public class UserCustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(name = "owner_discord_user_id", nullable = false)
    private Long ownerDiscordUserId;

    @Column(length = 32)
    private String color;

    @Column(name = "icon_id")
    private Long iconId;

    @Column(name = "color2", length = 32)
    private String color2;

    @Column(name = "role_id")
    private Long roleId;
}
