package com.Brafurries.API.repository.portaria;

import com.Brafurries.API.entity.portaria.PortariaAccountRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortariaAccountReleaseRepository extends JpaRepository<PortariaAccountRelease, Integer> {
}
