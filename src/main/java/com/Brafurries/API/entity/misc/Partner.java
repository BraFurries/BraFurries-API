package com.Brafurries.API.entity.misc;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "partners")
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_id")
    private Community community;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 96, unique = true)
    private String slug;

    @Column(nullable = false, length = 32)
    private String category = "community";

    @Column(nullable = false, length = 32)
    private String status = "active";

    @Column(length = 2048)
    private String description;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(name = "website_url", length = 1024)
    private String websiteUrl;

    @Column(name = "contact_name", length = 128)
    private String contactName;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "representative_user_id")
    private User representativeUser;

    @Column(length = 128)
    private String secret;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "partner", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PartnerLink> links = new LinkedHashSet<>();

    @OneToMany(mappedBy = "partner", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PartnerExternalLink> externalLinks = new LinkedHashSet<>();

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
