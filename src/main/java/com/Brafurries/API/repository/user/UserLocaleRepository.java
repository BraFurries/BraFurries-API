package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserLocale;
import com.Brafurries.API.entity.user.User;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserLocaleRepository extends JpaRepository<UserLocale, Integer> {
    Optional<UserLocale> findByUser(User user);
    List<UserLocale> findAllByUserIdOrderByIdAsc(Integer userId);
}
