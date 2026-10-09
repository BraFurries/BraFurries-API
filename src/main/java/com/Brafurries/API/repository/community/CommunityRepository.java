package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.Community;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityRepository extends JpaRepository<Community, Integer> {
    Page<Community> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query("""
        SELECT community
        FROM Community community
        WHERE community.ownerUser.id = :ownerUserId
        ORDER BY LOWER(community.name), community.id
        """)
    List<Community> findOwnedByUserId(@Param("ownerUserId") Integer ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT community FROM Community community WHERE community.id = :id")
    Optional<Community> findByIdForOwnershipClaim(@Param("id") Integer id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT community FROM Community community WHERE community.id = :id")
    Optional<Community> findByIdForTeamStructureUpdate(@Param("id") Integer id);
}
