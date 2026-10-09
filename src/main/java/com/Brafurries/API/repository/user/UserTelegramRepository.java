package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.entity.user.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserTelegramRepository extends JpaRepository<UserTelegram, Integer> {
    Optional<UserTelegram> findByUser(User user);

    List<UserTelegram> findByUserIdIn(Collection<Integer> userIds);

    List<UserTelegram> findAllByUserIdOrderByIdAsc(Integer userId);
}
