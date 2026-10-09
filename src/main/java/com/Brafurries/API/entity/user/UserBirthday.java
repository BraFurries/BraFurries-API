package com.Brafurries.API.entity.user;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_birthday")
public class UserBirthday {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "post_informed_date")
    private LocalDate postInformedDate;

    @Column(nullable = false)
    private Boolean verified;

    @Column(nullable = false)
    private Boolean registered;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "18_plus", nullable = false)
    private Boolean plus18;
}
