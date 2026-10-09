package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "display_name", length = 32)
    private String displayName;

    @Column(name = "username", length = 32)
    private String username;

    @Column(nullable = true, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = true, length = 255)
    private String passwordHash;

    @Column(name = "first_access", nullable = false)
    private Boolean firstAccess = true;

    @Column(name = "profile_image_key", length = 512)
    private String profileImageKey;

    @Column(name = "profile_image_url", length = 1024)
    private String profileImageUrl;

    @Column(name = "profile_image_content_type", length = 100)
    private String profileImageContentType;

    @Column(name = "profile_image_updated_at")
    private LocalDateTime profileImageUpdatedAt;
}
