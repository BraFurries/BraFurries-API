package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserLevel;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

@Repository
public interface UserLevelRepository extends JpaRepository<UserLevel, Integer> {
    @EntityGraph(attributePaths = {"communityDiscord"})
    Optional<UserLevel> findByUserIdAndCommunityDiscordCommunityId(Integer userId, Integer communityId);
    @EntityGraph(attributePaths = {"communityDiscord"})
    List<UserLevel> findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(Integer userId, Integer communityId);
}
