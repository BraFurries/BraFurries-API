package com.Brafurries.API.entity.misc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "locale")
public class Locale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "locale_abbrev", nullable = false, length = 8, unique = true)
    private String localeAbbrev;

    @Column(name = "locale_name", nullable = false, length = 64, unique = true)
    private String localeName;
}
