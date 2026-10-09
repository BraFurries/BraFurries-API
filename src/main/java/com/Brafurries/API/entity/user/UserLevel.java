package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_level")
public class UserLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_guild_id", nullable = false, referencedColumnName = "guild_id")
    private CommunityDiscord communityDiscord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_xp", nullable = false)
    private Long totalXp;

    @Column(name = "current_level", nullable = false)
    private Integer currentLevel;

    @Column(name = "daily_recs_count")
    private Integer dailyRecsCount;

    @Column(name = "weekly_bump_xp")
    private Integer weeklyBumpXp;

    @Column(name = "xp_awarded_voice_today", nullable = false)
    private Integer xpAwardedVoiceToday;

    @Column(name = "xp_awarded_voice_day")
    private LocalDate xpAwardedVoiceDay;

    @Column(name = "xp_awarded_today", nullable = false)
    private Integer xpAwardedToday;

    @Column(name = "xp_awarded_day")
    private LocalDate xpAwardedDay;

    @Column(name = "xp_awarded_text_today", nullable = false)
    private Integer xpAwardedTextToday;

    @Column(name = "xp_awarded_text_day")
    private LocalDate xpAwardedTextDay;
}
