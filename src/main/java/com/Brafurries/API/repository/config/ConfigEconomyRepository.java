package com.Brafurries.API.repository.config;

import com.Brafurries.API.entity.config.ConfigEconomy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfigEconomyRepository extends JpaRepository<ConfigEconomy, Integer> {
}
