package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.ConfigLogs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigLogsRepository extends JpaRepository<ConfigLogs, Integer> {
}
