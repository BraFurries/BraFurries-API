package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserEconomy;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserEconomyRepository extends JpaRepository<UserEconomy, Integer> {
    Optional<UserEconomy> findByUserAndCommunityDiscord(User user, CommunityDiscord communityDiscord);
    List<UserEconomy> findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(Integer userId, Integer communityId);
}
