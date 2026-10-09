package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_telegram")
public class UserTelegram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "telegram_user_id", nullable = false)
    private Integer telegramUserId;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "display_name", nullable = false, length = 32)
    private String displayName;
}
