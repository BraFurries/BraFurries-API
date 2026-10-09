package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.PartnerLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerLinkRepository extends JpaRepository<PartnerLink, Integer> {

    @Query("""
        SELECT DISTINCT pl.targetId
        FROM PartnerLink pl
        WHERE pl.targetType = 'event'
          AND pl.targetId IN :eventIds
          AND LOWER(pl.partner.status) = 'active'
        """)
    List<Integer> findActivePartnerEventIds(@Param("eventIds") Collection<Integer> eventIds);

    @Query("""
        SELECT pl
        FROM PartnerLink pl
        JOIN FETCH pl.partner p
        WHERE pl.targetType = 'event'
          AND pl.targetId = :eventId
        ORDER BY p.id DESC
        """)
    List<PartnerLink> findEventPartnerLinks(@Param("eventId") Integer eventId);
}
