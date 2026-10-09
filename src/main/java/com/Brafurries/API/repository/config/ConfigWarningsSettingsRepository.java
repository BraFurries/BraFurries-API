package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.ConfigWarningsSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigWarningsSettingsRepository extends JpaRepository<ConfigWarningsSettings, Integer> {
}
