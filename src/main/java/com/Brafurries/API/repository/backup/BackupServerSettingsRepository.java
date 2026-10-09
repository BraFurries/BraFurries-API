package com.Brafurries.API.repository.backup;

import com.Brafurries.API.entity.backup.BackupServerSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupServerSettingsRepository extends JpaRepository<BackupServerSettings, Integer> {
}
