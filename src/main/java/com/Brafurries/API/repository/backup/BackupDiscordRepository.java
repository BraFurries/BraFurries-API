package com.Brafurries.API.repository.backup;

import com.Brafurries.API.entity.backup.BackupDiscord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupDiscordRepository extends JpaRepository<BackupDiscord, Integer> {
}
