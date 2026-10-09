package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_permissions")
public class UserPermission {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Boolean admin;

    @Column(nullable = false)
    private Boolean developer;

    @Column(name = "create_events", nullable = false)
    private Boolean createEvents;

    @Column(name = "edit_events", nullable = false)
    private Boolean editEvents;

    @Column(name = "create_users", nullable = false)
    private Boolean createUsers;

    @Column(name = "edit_users", nullable = false)
    private Boolean editUsers;
}
