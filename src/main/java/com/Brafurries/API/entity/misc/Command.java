package com.Brafurries.API.entity.misc;

import jakarta.persistence.*;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "commands")
public class Command {

    @Id
    @Column(nullable = false)
    private Integer id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "cooldown")
    private LocalTime cooldown;
}
