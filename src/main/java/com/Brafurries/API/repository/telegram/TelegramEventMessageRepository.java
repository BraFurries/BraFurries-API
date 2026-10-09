package com.Brafurries.API.repository.telegram;

import com.Brafurries.API.entity.telegram.TelegramEventMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository("telegramTelegramEventMessageRepository")
public interface TelegramEventMessageRepository extends JpaRepository<TelegramEventMessage, Integer> {
}
