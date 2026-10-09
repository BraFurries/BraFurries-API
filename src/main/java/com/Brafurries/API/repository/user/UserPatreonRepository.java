package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserPatreon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserPatreonRepository extends JpaRepository<UserPatreon, Integer> {
}
