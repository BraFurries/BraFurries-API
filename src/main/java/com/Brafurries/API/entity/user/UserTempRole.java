package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_temp_roles")
public class UserTempRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "disc_community_id", nullable = false)
    private CommunityDiscord discordCommunity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "disc_user_id", nullable = false)
    private UserDiscord discordUser;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "expiring_date", nullable = false)
    private LocalDateTime expiringDate;

    @Column(nullable = false, length = 100)
    private String reason;
}
