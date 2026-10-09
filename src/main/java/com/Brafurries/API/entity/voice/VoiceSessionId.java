package com.Brafurries.API.entity.voice;

import java.io.Serializable;
import lombok.Data;

@Data
public class VoiceSessionId implements Serializable {
    private Long serverGuildId;
    private Long discordUserId;
}
