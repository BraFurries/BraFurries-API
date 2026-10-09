package com.Brafurries.API.entity.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "api_clients")
public class ApiClient {

    public enum ClientType {
        internal,
        partner,
        bot,
        service
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "client_key", nullable = false, length = 100, unique = true)
    private String clientKey;

    @Column(nullable = false)
    private Boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClientType type = ClientType.partner;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
