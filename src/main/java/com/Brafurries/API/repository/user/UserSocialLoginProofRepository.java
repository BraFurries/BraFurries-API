package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserSocialLoginProof;
import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSocialLoginProofRepository extends JpaRepository<UserSocialLoginProof, Long> {
    Optional<UserSocialLoginProof> findByTicketHash(String ticketHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT proof FROM UserSocialLoginProof proof WHERE proof.ticketHash = :hash")
    Optional<UserSocialLoginProof> findByTicketHashForUpdate(@Param("hash") String hash);

    long deleteByExpiresAtBefore(LocalDateTime expiredBefore);
}
