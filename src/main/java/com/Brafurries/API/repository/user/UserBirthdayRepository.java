package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserBirthday;
import com.Brafurries.API.entity.user.User;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserBirthdayRepository extends JpaRepository<UserBirthday, Integer> {
    Optional<UserBirthday> findByUser(User user);
    List<UserBirthday> findAllByUserId(Integer userId);
}
