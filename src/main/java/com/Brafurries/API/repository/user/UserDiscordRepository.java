package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDiscordRepository extends JpaRepository<UserDiscord, Integer> {
    @EntityGraph(attributePaths = "user")
    Optional<UserDiscord> findByDiscordUserId(Long discordUserId);

    Optional<UserDiscord> findByUser(User user);

    @EntityGraph(attributePaths = "user")
    List<UserDiscord> findByUserIdIn(Collection<Integer> userIds);

    @EntityGraph(attributePaths = "user")
    List<UserDiscord> findByDiscordUserIdIn(Collection<Long> discordUserIds);

    List<UserDiscord> findAllByUserIdOrderByIdAsc(Integer userId);
}
