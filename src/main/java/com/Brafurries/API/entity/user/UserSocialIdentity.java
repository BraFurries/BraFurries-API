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

/**
 * Immutable external OAuth subject, verified by its provider.
 * Neither an e-mail address nor a Discord nickname is an identity key.
 */
@Entity
@Table(name = "user_social_identities")
@Getter
@Setter
public class UserSocialIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_subject", nullable = false, length = 128)
    private String providerSubject;

    @Column(name = "verified_email", length = 255)
    private String verifiedEmail;

    // This flag governs authentication only, not the Discord/Community link.
    @Column(name = "login_enabled", nullable = false)
    private Boolean loginEnabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_verified_at", nullable = false)
    private LocalDateTime lastVerifiedAt;

    @PrePersist
    void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (lastVerifiedAt == null) lastVerifiedAt = now;
    }
}
