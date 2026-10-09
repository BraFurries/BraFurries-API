package com.Brafurries.API.repository.backup;

import com.Brafurries.API.entity.backup.BackupChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupChannelRepository extends JpaRepository<BackupChannel, Integer> {
}
