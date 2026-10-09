package com.Brafurries.API.entity.api;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "api_user_roles")
public class ApiUserRole {

    @EmbeddedId
    private ApiUserRoleId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("roleId")
    @JoinColumn(name = "role_id", nullable = false)
    private ApiRole role;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Getter
    @Setter
    @Embeddable
    @EqualsAndHashCode
    public static class ApiUserRoleId implements Serializable {

        @Column(name = "user_id")
        private Integer userId;

        @Column(name = "role_id")
        private Integer roleId;
    }
}
