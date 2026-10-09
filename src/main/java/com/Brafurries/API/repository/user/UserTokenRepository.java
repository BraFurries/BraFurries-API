package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserToken;
import com.Brafurries.API.entity.user.UserTokenType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    Optional<UserToken> findByTokenAndType(String token, UserTokenType type);

    boolean existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(User user, UserTokenType type);

    List<UserToken> findByTypeAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtBefore(UserTokenType type, Instant instant);

    @Transactional
    void deleteByUserAndType(User user, UserTokenType type);

    @Transactional
    void deleteByUser(User user);

    @Transactional
    long deleteByTypeAndExpiresAtBefore(UserTokenType type, Instant instant);

    @Transactional
    long deleteByUsedAtIsNotNullAndExpiresAtBefore(Instant instant);

    @Transactional
    long deleteByRevokedAtIsNotNullAndExpiresAtBefore(Instant instant);
}
