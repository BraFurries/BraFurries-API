package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiUserRole;
import com.Brafurries.API.entity.api.ApiUserRole.ApiUserRoleId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiUserRoleRepository extends JpaRepository<ApiUserRole, ApiUserRoleId> {

    @Query("select distinct ur.role.name from ApiUserRole ur where ur.user.id = :userId order by ur.role.name")
    List<String> findRoleNamesByUserId(@Param("userId") Integer userId);

    @Query("select ur from ApiUserRole ur join fetch ur.role where ur.user.id = :userId")
    List<ApiUserRole> findWithRoleByUserId(@Param("userId") Integer userId);

    Optional<ApiUserRole> findByUserIdAndRoleId(Integer userId, Integer roleId);

    @Modifying
    @Query("delete from ApiUserRole ur where ur.user.id = :userId and lower(ur.role.name) in :roleNames")
    void deleteAdminScreenRolesByUserId(
        @Param("userId") Integer userId,
        @Param("roleNames") Collection<String> roleNames
    );
}
