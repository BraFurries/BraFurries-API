package com.Brafurries.API.repository.invite;

import com.Brafurries.API.entity.invite.Invite;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InviteRepository extends JpaRepository<Invite, Long> {

    @EntityGraph(attributePaths = {"requestedByUser", "targetUser"})
    Optional<Invite> findByToken(String token);
}
