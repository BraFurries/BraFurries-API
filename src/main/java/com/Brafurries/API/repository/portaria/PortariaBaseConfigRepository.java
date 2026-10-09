package com.Brafurries.API.repository.portaria;

import com.Brafurries.API.entity.portaria.PortariaBaseConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortariaBaseConfigRepository extends JpaRepository<PortariaBaseConfig, Integer> {
    Optional<PortariaBaseConfig> findByServerGuildId(Long serverGuildId);
}
