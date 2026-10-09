package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiPermissionRepository extends JpaRepository<ApiPermission, Integer> {
}
