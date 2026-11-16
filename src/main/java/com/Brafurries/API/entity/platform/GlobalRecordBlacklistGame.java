package com.Brafurries.API.entity.platform;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "record_global_blacklisted_games")
public class GlobalRecordBlacklistGame {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "game_name", nullable = false, length = 50, unique = true)
    private String gameName;
}
