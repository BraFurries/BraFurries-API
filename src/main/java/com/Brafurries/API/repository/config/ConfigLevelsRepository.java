package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.ConfigLevels;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigLevelsRepository extends JpaRepository<ConfigLevels, Integer> {
    @Query("""
        SELECT config
        FROM ConfigLevels config
        JOIN FETCH config.communityDiscord link
        JOIN FETCH link.community
        WHERE link.guildId = :guildId
        """)
    Optional<ConfigLevels> findByGuildId(@Param("guildId") Long guildId);

    @Modifying
    @Query(
        value = "INSERT IGNORE INTO config_levels (server_guild_id) VALUES (:guildId)",
        nativeQuery = true
    )
    int ensureGuildConfig(@Param("guildId") Long guildId);
}
