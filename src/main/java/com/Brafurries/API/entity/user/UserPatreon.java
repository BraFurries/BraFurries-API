package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_patreon")
public class UserPatreon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "display_name", nullable = false, length = 32)
    private String displayName;

    @Column(name = "patreon_user_id", nullable = false)
    private Long patreonUserId;
}
