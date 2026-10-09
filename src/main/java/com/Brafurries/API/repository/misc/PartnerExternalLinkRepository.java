package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.PartnerExternalLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerExternalLinkRepository extends JpaRepository<PartnerExternalLink, Integer> {
}
