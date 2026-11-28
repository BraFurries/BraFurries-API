package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Integer>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);

    /** Lock by the JWT principal in one query, avoiding an earlier stale snapshot. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    /** Partner representatives: name/username only, never search private emails. */
    @Query("""
        SELECT u FROM User u
        WHERE LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY COALESCE(u.displayName, u.username, ''), u.id
        """)
    Page<User> searchPartnerRepresentatives(@Param("search") String search, Pageable pageable);


    @Modifying
    @Query("""
        UPDATE User user
           SET user.displayName = :displayName
         WHERE user.id = :userId
        """)
    int updateDisplayNameById(@Param("userId") Integer userId, @Param("displayName") String displayName);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Integer id);

    @Query("""
        SELECT u
        FROM User u
        WHERE :search IS NULL
           OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :search, '%'))
        """)
    Page<User> searchAdminUsers(@Param("search") String search, Pageable pageable);

    /** Platform-wide admin search; callers retain the existing authorization checks. */
    default Page<User> searchAdminUsersFiltered(
        String search, Long discordUserId, String status, String role, LocalDate today, Pageable pageable
    ) {
        return findAll(AdminUserSearchSpecification.matching(search, discordUserId, status, role, today), pageable);
    }

    default Page<User> searchCommunityMembers(Integer communityId, String search, Pageable pageable) {
        return findAll(CommunityUserSearchSpecification.matching(communityId, search, false), pageable);
    }


    @Query(
        value = """
            SELECT u FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u AND membership.community.id = :communityId
            )
            AND EXISTS (
                SELECT identity.id
                FROM UserDiscord identity
                WHERE identity.user = u AND identity.discordUserId = :discordUserId
            )
            """,
        countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u AND membership.community.id = :communityId
            )
            AND EXISTS (
                SELECT identity.id
                FROM UserDiscord identity
                WHERE identity.user = u AND identity.discordUserId = :discordUserId
            )
            """
    )
    Page<User> searchCommunityMembersByDiscordId(
        @Param("communityId") Integer communityId,
        @Param("discordUserId") Long discordUserId,
        Pageable pageable
    );

    default Page<User> searchEligibleCommunityTeamCandidates(Integer communityId, String search, Pageable pageable) {
        return findAll(CommunityUserSearchSpecification.matching(communityId, search, true), pageable);
    }

}
