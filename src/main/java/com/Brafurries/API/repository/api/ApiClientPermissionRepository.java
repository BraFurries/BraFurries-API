package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiClientPermission;
import com.Brafurries.API.entity.api.ApiClientPermission.ApiClientPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiClientPermissionRepository extends JpaRepository<ApiClientPermission, ApiClientPermissionId> {
}
