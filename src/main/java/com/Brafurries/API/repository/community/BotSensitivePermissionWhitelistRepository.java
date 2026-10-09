package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.BotSensitivePermissionWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BotSensitivePermissionWhitelistRepository extends JpaRepository<BotSensitivePermissionWhitelist, Integer> {
}
