package com.Brafurries.API.repository.voice;

import com.Brafurries.API.entity.voice.VoiceSession;
import com.Brafurries.API.entity.voice.VoiceSessionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoiceSessionRepository extends JpaRepository<VoiceSession, VoiceSessionId> {
    List<VoiceSession> findByServerGuildId(Long serverGuildId);
}
