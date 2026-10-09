package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiRolePermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiRolePermissionRepository extends JpaRepository<ApiRolePermission, ApiRolePermission.ApiRolePermissionId> {

    @Query(
        "select distinct rp.permission.name " +
            "from ApiRolePermission rp " +
            "join ApiUserRole ur on ur.role.id = rp.role.id " +
            "where ur.user.id = :userId " +
            "order by rp.permission.name"
    )
    List<String> findPermissionNamesByUserId(@Param("userId") Integer userId);
}
