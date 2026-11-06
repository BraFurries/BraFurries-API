package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserDiscordLinkChallenge;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDiscordLinkChallengeRepository extends JpaRepository<UserDiscordLinkChallenge, Long> {
    Optional<UserDiscordLinkChallenge> findByTicketHash(String ticketHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT challenge FROM UserDiscordLinkChallenge challenge WHERE challenge.ticketHash = :hash")
    Optional<UserDiscordLinkChallenge> findByTicketHashForUpdate(@Param("hash") String hash);

    long deleteByExpiresAtBefore(LocalDateTime expiredBefore);
}
