package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserSocialOnboardingChallenge;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSocialOnboardingChallengeRepository
        extends JpaRepository<UserSocialOnboardingChallenge, Long> {
    Optional<UserSocialOnboardingChallenge> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM UserSocialOnboardingChallenge c WHERE c.tokenHash = :hash")
    Optional<UserSocialOnboardingChallenge> findByTokenHashForUpdate(@Param("hash") String tokenHash);

    @Modifying
    @Query("DELETE FROM UserSocialOnboardingChallenge c WHERE c.expiresAt < :threshold")
    int deleteExpired(@Param("threshold") LocalDateTime threshold);
}
