package com.Brafurries.API.entity.config;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_levels", uniqueConstraints = {
        @UniqueConstraint(name = "server_guild_id", columnNames = "server_guild_id")
})
public class ConfigLevels {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @Column(name = "clear_on_exit", nullable = false)
    private Boolean clearOnExit;

    @Column(name = "levelup_warning", nullable = false)
    private Boolean levelupWarning;

    @Column(name = "levelup_warning_channel")
    private Long levelupWarningChannel;

    @Column(name = "level_up_message", nullable = false, columnDefinition = "text")
    private String levelUpMessage;

    @Column(name = "multiplier", precision = 20, scale = 3)
    private BigDecimal multiplier;

    @Column(name = "phase1_k", nullable = false, precision = 20, scale = 3)
    private BigDecimal phase1K;

    @Column(name = "phase1_p", nullable = false, precision = 20, scale = 3)
    private BigDecimal phase1P;

    @Column(name = "phase1_b", nullable = false, precision = 20, scale = 3)
    private BigDecimal phase1B;

    @Column(name = "daily_combo")
    private Integer dailyCombo;

    @Column(name = "combo_multiplier", precision = 20, scale = 3)
    private BigDecimal comboMultiplier;

    @Column(name = "xp_base_per_min", nullable = false)
    private Integer xpBasePerMin;

    @Column(name = "voice_social_bonus_pct", nullable = false, precision = 8, scale = 4)
    private BigDecimal voiceSocialBonusPct;

    @Column(name = "voice_social_bonus_min_humans", nullable = false)
    private Integer voiceSocialBonusMinHumans;

    @Column(name = "voice_diminishing_window1_minutes", nullable = false)
    private Integer voiceDiminishingWindow1Minutes;

    @Column(name = "voice_diminishing_window2_minutes", nullable = false)
    private Integer voiceDiminishingWindow2Minutes;

    @Column(name = "voice_diminishing_factor2", nullable = false, precision = 8, scale = 4)
    private BigDecimal voiceDiminishingFactor2;

    @Column(name = "voice_diminishing_factor3", nullable = false, precision = 8, scale = 4)
    private BigDecimal voiceDiminishingFactor3;

    @Column(name = "voice_daily_cap_xp", nullable = false)
    private Integer voiceDailyCapXp;

    @Column(name = "text_daily_cap_xp", nullable = false)
    private Integer textDailyCapXp;

    @Column(name = "global_daily_cap_xp", nullable = false)
    private Integer globalDailyCapXp;

    @Column(name = "text_xp_enabled", nullable = false)
    private Boolean textXpEnabled;

    @Column(name = "voice_xp_enabled", nullable = false)
    private Boolean voiceXpEnabled;

    @Column(name = "text_xp_base_min", nullable = false)
    private Integer textXpBaseMin;

    @Column(name = "text_xp_base_max", nullable = false)
    private Integer textXpBaseMax;

    @Column(name = "text_xp_cooldown_min_seconds", nullable = false)
    private Integer textXpCooldownMinSeconds;

    @Column(name = "text_xp_cooldown_max_seconds", nullable = false)
    private Integer textXpCooldownMaxSeconds;

    @Column(name = "level_reconcile_required", nullable = false)
    private Boolean levelReconcileRequired;

    @Column(name = "level_reconcile_requested_at")
    private LocalDateTime levelReconcileRequestedAt;

    @Column(name = "level_reconciled_at")
    private LocalDateTime levelReconciledAt;
}
