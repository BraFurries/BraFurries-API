package com.Brafurries.API.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_identity_links")
public class UserIdentityLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_a_id", nullable = false)
    private User userA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_b_id", nullable = false)
    private User userB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserIdentityLinkStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_source", nullable = false, length = 32)
    private UserIdentityLinkSource linkSource;

    @Column(length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by_user_id")
    private User createdByUser;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id")
    private User revokedByUser;

    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
