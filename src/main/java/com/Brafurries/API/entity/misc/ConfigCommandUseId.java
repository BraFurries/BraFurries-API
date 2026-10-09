package com.Brafurries.API.entity.misc;

import java.io.Serializable;
import lombok.Data;

@Data
public class ConfigCommandUseId implements Serializable {
    private Integer user;
    private Integer command;
}
