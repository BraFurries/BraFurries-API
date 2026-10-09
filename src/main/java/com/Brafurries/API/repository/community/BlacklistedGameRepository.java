package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.BlacklistedGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BlacklistedGameRepository extends JpaRepository<BlacklistedGame, Integer> {
}
