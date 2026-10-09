package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.PartnerToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerTokenRepository extends JpaRepository<PartnerToken, Integer> {

    void deleteByPartnerId(Integer partnerId);
}
