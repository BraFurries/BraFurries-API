package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.ConfigCommandUse;
import com.Brafurries.API.entity.misc.ConfigCommandUseId;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfigCommandUseRepository extends JpaRepository<ConfigCommandUse, ConfigCommandUseId> {

    long countByDtmLastUsageGreaterThanEqual(LocalDateTime from);

    @Query("""
        SELECT c.command.name AS command, COUNT(c) AS uses
        FROM ConfigCommandUse c
        WHERE c.dtmLastUsage >= :from
        GROUP BY c.command.name
        ORDER BY COUNT(c) DESC
        """)
    List<TopCommandProjection> findTopCommandsSince(@Param("from") LocalDateTime from, Pageable pageable);

    interface TopCommandProjection {
        String getCommand();
        Long getUses();
    }
}
