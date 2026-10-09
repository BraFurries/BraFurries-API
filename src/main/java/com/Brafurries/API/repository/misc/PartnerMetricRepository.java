package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.PartnerMetric;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerMetricRepository extends JpaRepository<PartnerMetric, Integer> {

    void deleteByPartner_Id(Integer partnerId);

    @Query("""
        SELECT pm.partner.id AS partnerId,
               COALESCE(SUM(pm.clicks), 0) AS clicks,
               COALESCE(SUM(pm.invites), 0) AS invites
        FROM PartnerMetric pm
        WHERE pm.partner.id IN :partnerIds
        GROUP BY pm.partner.id
        """)
    List<PartnerMetricTotalsProjection> sumTotalsByPartnerIds(@Param("partnerIds") Collection<Integer> partnerIds);

    interface PartnerMetricTotalsProjection {
        Integer getPartnerId();
        Long getClicks();
        Long getInvites();
    }
}
