package com.Brafurries.API.entity.api;

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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "api_role_permissions")
public class ApiRolePermission {

    @EmbeddedId
    private ApiRolePermissionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("roleId")
    @JoinColumn(name = "role_id", nullable = false)
    private ApiRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("permissionId")
    @JoinColumn(name = "permission_id", nullable = false)
    private ApiPermission permission;

    @Getter
    @Setter
    @Embeddable
    @EqualsAndHashCode
    public static class ApiRolePermissionId implements Serializable {

        @Column(name = "role_id")
        private Integer roleId;

        @Column(name = "permission_id")
        private Integer permissionId;
    }
}
