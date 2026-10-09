package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_server_settings")
public class ConfigServerSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(name = "has_levels", nullable = false)
    private Boolean hasLevels;

    @Column(name = "has_economy", nullable = false)
    private Boolean hasEconomy;

    @Column(name = "has_role_division", nullable = false)
    private Boolean hasRoleDivision;

    @Column(name = "has_gpt_enabled", nullable = false)
    private Boolean hasGptEnabled;

    @Column(name = "gpt_model", nullable = false, length = 32)
    private String gptModel;

    @Column(name = "general_chat_id", nullable = false)
    private Long generalChatId;

    @Column(name = "staff_roles", columnDefinition = "text")
    private String staffRoles;

    @Column(name = "birthday_channel_id")
    private Long birthdayChannelId;

    @Column(name = "vip_roles", columnDefinition = "text")
    private String vipRoles;

    @Column(name = "ai_openai_token_encrypted", columnDefinition = "text")
    private String aiOpenaiTokenEncrypted;
}
