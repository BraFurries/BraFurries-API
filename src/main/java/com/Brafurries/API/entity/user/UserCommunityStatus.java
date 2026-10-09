package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_community_status")
public class UserCommunityStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(name = "member_since", nullable = false)
    private LocalDateTime memberSince;

    @Column(name = "last_join_date")
    private LocalDateTime lastJoinDate;

    @Column(nullable = false)
    private Boolean approved;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "invite_link_used", length = 255)
    private String inviteLinkUsed;

    @Column(name = "invited_by", length = 255)
    private String invitedBy;

    @Column(name = "is_vip", nullable = false)
    private Boolean isVip;

    @Column(name = "is_partner", nullable = false)
    private Boolean isPartner;

    @Column(nullable = false)
    private Boolean banned;

    @Column(name = "is_present")
    private Boolean isPresent;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "birthday_mentionable", nullable = false)
    private Boolean birthdayMentionable;

    @Column(name = "approval_required")
    private Boolean approvalRequired;

    @Column(name = "display_name", length = 100)
    private String displayName;
}
