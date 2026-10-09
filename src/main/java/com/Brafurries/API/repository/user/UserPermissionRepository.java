package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermission, Integer> {
}
