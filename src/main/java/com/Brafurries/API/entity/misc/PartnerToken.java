package com.Brafurries.API.entity.misc;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "partner_token")
public class PartnerToken {

    @Id
    @Column(name = "partner_id")
    private Integer partnerId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @Column(nullable = false, length = 128)
    private String token;

    @Column(name = "expiration_dtm", nullable = false)
    private LocalDateTime expirationDtm;
}
