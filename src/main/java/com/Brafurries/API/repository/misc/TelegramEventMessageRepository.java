package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.TelegramEventMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelegramEventMessageRepository extends JpaRepository<TelegramEventMessage, Integer> {
    Optional<TelegramEventMessage> findByChannelName(String channelName);
}
