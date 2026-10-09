package com.Brafurries.API.entity.misc;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_command_uses")
@IdClass(ConfigCommandUseId.class)
public class ConfigCommandUse {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "command_id", nullable = false)
    private Command command;

    @Column(name = "dtm_last_usage", nullable = false)
    private LocalDateTime dtmLastUsage;
}
