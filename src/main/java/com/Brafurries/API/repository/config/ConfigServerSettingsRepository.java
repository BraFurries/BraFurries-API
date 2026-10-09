package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.ConfigServerSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigServerSettingsRepository extends JpaRepository<ConfigServerSettings, Integer> {
}
