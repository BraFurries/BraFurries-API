package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserRefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Integer> {
    Optional<UserRefreshToken> findByToken(String token);
    void deleteByUser(User user);
}
