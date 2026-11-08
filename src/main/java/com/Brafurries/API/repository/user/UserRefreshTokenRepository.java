package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserRefreshToken;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Integer> {
    Optional<UserRefreshToken> findByToken(String token);
    void deleteByUser(User user);

    /** Compare-and-revoke under the owning User's row lock; protects concurrent rotations. */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE UserRefreshToken token
           SET token.revokedAt = :now
         WHERE token.token = :token
           AND token.revokedAt IS NULL
           AND token.expiresAt > :now
        """)
    int revokeActiveToken(@Param("token") String token, @Param("now") Instant now);
}
