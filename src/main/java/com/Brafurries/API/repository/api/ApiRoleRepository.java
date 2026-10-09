package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiRoleRepository extends JpaRepository<ApiRole, Integer> {
    Optional<ApiRole> findByNameIgnoreCase(String name);
}
