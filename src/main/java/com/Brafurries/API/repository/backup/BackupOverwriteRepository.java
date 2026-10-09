package com.Brafurries.API.repository.backup;

import com.Brafurries.API.entity.backup.BackupOverwrite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupOverwriteRepository extends JpaRepository<BackupOverwrite, Integer> {
}
