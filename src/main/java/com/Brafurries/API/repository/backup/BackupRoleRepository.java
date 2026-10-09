package com.Brafurries.API.repository.backup;

import com.Brafurries.API.entity.backup.BackupRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupRoleRepository extends JpaRepository<BackupRole, Integer> {
}
