package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.DiscordServerMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscordServerMessageRepository extends JpaRepository<DiscordServerMessage, Integer> {
}
