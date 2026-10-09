package com.Brafurries.API.repository.voice;

import com.Brafurries.API.entity.voice.VoiceXpAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoiceXpAuditLogRepository extends JpaRepository<VoiceXpAuditLog, Long> {
    List<VoiceXpAuditLog> findByServerGuildIdAndDiscordUserId(Long serverGuildId, Long discordUserId);
}
