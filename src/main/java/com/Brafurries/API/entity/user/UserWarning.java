package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_warnings")
public class UserWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(nullable = false)
    private Boolean expired;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applied_by", nullable = false)
    private User appliedBy;
}
