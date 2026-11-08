package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserSocialLoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSocialLoginEventRepository extends JpaRepository<UserSocialLoginEvent, Long> {
}
