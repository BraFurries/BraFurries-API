package com.Brafurries.API.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.Getter;
import lombok.Setter;

/** Immutable, transactional record of a provider login-permission change. */
@Getter
@Setter
@Entity
@Table(name = "user_social_login_events")
public class UserSocialLoginEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "social_identity_id", nullable = false)
    private UserSocialIdentity identity;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "previous_login_enabled", nullable = false)
    private Boolean previousLoginEnabled;

    @Column(name = "next_login_enabled", nullable = false)
    private Boolean nextLoginEnabled;

    @Column(name = "previous_auth_version", nullable = false)
    private Long previousAuthVersion;

    @Column(name = "next_auth_version", nullable = false)
    private Long nextAuthVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    public void onCreate() {
        if (occurredAt == null) occurredAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
