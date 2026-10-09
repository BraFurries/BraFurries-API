package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_bans")
public class UserBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 256)
    private String reason;

    @Column(name = "can_appeal", nullable = false)
    private Boolean canAppeal;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_by")
    private User appliedBy;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    @Column(name = "revocation_reason", length = 512)
    private String revocationReason;
}
