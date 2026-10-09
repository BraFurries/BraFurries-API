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
@Table(name = "api_client_permissions")
public class ApiClientPermission {

    @EmbeddedId
    private ApiClientPermissionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("clientId")
    @JoinColumn(name = "client_id", nullable = false)
    private ApiClient client;

    @Column(name = "permission", nullable = false, length = 100, insertable = false, updatable = false)
    private String permission;

    @Getter
    @Setter
    @Embeddable
    @EqualsAndHashCode
    public static class ApiClientPermissionId implements Serializable {

        @Column(name = "client_id")
        private Integer clientId;

        @Column(name = "permission", length = 100)
        private String permission;
    }
}
