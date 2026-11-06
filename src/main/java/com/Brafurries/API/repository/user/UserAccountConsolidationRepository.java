package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserAccountConsolidation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountConsolidationRepository
        extends JpaRepository<UserAccountConsolidation, Long> {
    boolean existsByAbsorbedUserId(Integer absorbedUserId);
}
