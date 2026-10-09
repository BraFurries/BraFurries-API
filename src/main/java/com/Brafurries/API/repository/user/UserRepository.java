package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByEmail(String email);

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

    @Query(
        value = """
            SELECT u
            FROM User u
            WHERE (
                (:discordUserId IS NOT NULL AND EXISTS (
                    SELECT ud.id
                    FROM UserDiscord ud
                    WHERE ud.user = u AND ud.discordUserId = :discordUserId
                ))
                OR (:discordUserId IS NULL AND (
                    :search IS NULL
                    OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR EXISTS (
                        SELECT identity.id
                        FROM UserDiscord identity
                        WHERE identity.user = u
                          AND (
                              LOWER(COALESCE(identity.username, ''))
                                  LIKE LOWER(CONCAT('%', :search, '%'))
                              OR LOWER(COALESCE(identity.displayName, ''))
                                  LIKE LOWER(CONCAT('%', :search, '%'))
                          )
                    )
                ))
            )
            AND (
                :status IS NULL
                OR (
                    :status = 'banned'
                    AND EXISTS (
                        SELECT ub.id
                        FROM UserBan ub
                        WHERE ub.user.id = u.id
                          AND ub.revokedAt IS NULL
                          AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
                    )
                )
                OR (
                    :status = 'active'
                    AND NOT EXISTS (
                        SELECT ub.id
                        FROM UserBan ub
                        WHERE ub.user.id = u.id
                          AND ub.revokedAt IS NULL
                          AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
                    )
                )
            )
            AND (
                :role IS NULL
                OR (
                    :role = 'admin'
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'admin'
                    )
                )
                OR (
                    :role = 'moderator'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'admin'
                    )
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'moderator'
                    )
                )
                OR (
                    :role = 'supporter'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) IN ('admin', 'moderator')
                    )
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'supporter'
                    )
                )
                OR (
                    :role = 'member'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) IN ('admin', 'moderator', 'supporter')
                    )
                )
            )
            """,
        countQuery = """
            SELECT COUNT(u)
            FROM User u
            WHERE (
                (:discordUserId IS NOT NULL AND EXISTS (
                    SELECT ud.id
                    FROM UserDiscord ud
                    WHERE ud.user = u AND ud.discordUserId = :discordUserId
                ))
                OR (:discordUserId IS NULL AND (
                    :search IS NULL
                    OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR EXISTS (
                        SELECT identity.id
                        FROM UserDiscord identity
                        WHERE identity.user = u
                          AND (
                              LOWER(COALESCE(identity.username, ''))
                                  LIKE LOWER(CONCAT('%', :search, '%'))
                              OR LOWER(COALESCE(identity.displayName, ''))
                                  LIKE LOWER(CONCAT('%', :search, '%'))
                          )
                    )
                ))
            )
            AND (
                :status IS NULL
                OR (
                    :status = 'banned'
                    AND EXISTS (
                        SELECT ub.id
                        FROM UserBan ub
                        WHERE ub.user.id = u.id
                          AND ub.revokedAt IS NULL
                          AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
                    )
                )
                OR (
                    :status = 'active'
                    AND NOT EXISTS (
                        SELECT ub.id
                        FROM UserBan ub
                        WHERE ub.user.id = u.id
                          AND ub.revokedAt IS NULL
                          AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
                    )
                )
            )
            AND (
                :role IS NULL
                OR (
                    :role = 'admin'
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'admin'
                    )
                )
                OR (
                    :role = 'moderator'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'admin'
                    )
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'moderator'
                    )
                )
                OR (
                    :role = 'supporter'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) IN ('admin', 'moderator')
                    )
                    AND EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) = 'supporter'
                    )
                )
                OR (
                    :role = 'member'
                    AND NOT EXISTS (
                        SELECT ur.user.id
                        FROM ApiUserRole ur
                        WHERE ur.user.id = u.id
                          AND LOWER(ur.role.name) IN ('admin', 'moderator', 'supporter')
                    )
                )
            )
            """
    )
    Page<User> searchAdminUsersFiltered(
        @Param("search") String search,
        @Param("discordUserId") Long discordUserId,
        @Param("status") String status,
        @Param("role") String role,
        @Param("today") LocalDate today,
        Pageable pageable
    );

    @Query(
        value = """
            SELECT u
            FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u
                  AND membership.community.id = :communityId
            )
            AND (
                :search IS NULL
                OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR EXISTS (
                    SELECT matchingMembership.id
                    FROM UserCommunityStatus matchingMembership
                    WHERE matchingMembership.user = u
                      AND matchingMembership.community.id = :communityId
                      AND LOWER(COALESCE(matchingMembership.displayName, ''))
                          LIKE LOWER(CONCAT('%', :search, '%'))
                )
                OR EXISTS (
                    SELECT identity.id
                    FROM UserDiscord identity
                    WHERE identity.user = u
                      AND (
                          LOWER(COALESCE(identity.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(COALESCE(identity.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                )
            )
            """,
        countQuery = """
            SELECT COUNT(u)
            FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u
                  AND membership.community.id = :communityId
            )
            AND (
                :search IS NULL
                OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR EXISTS (
                    SELECT matchingMembership.id
                    FROM UserCommunityStatus matchingMembership
                    WHERE matchingMembership.user = u
                      AND matchingMembership.community.id = :communityId
                      AND LOWER(COALESCE(matchingMembership.displayName, ''))
                          LIKE LOWER(CONCAT('%', :search, '%'))
                )
                OR EXISTS (
                    SELECT identity.id
                    FROM UserDiscord identity
                    WHERE identity.user = u
                      AND (
                          LOWER(COALESCE(identity.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(COALESCE(identity.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                )
            )
            """
    )
    Page<User> searchCommunityMembers(
        @Param("communityId") Integer communityId,
        @Param("search") String search,
        Pageable pageable
    );


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

    @Query(
        value = """
            SELECT u
            FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u
                  AND membership.community.id = :communityId
                  AND membership.approved = true
                  AND membership.banned = false
                  AND membership.isPresent = true
            )
            AND (
                :search IS NULL
                OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR EXISTS (
                    SELECT matchingMembership.id
                    FROM UserCommunityStatus matchingMembership
                    WHERE matchingMembership.user = u
                      AND matchingMembership.community.id = :communityId
                      AND LOWER(COALESCE(matchingMembership.displayName, ''))
                          LIKE LOWER(CONCAT('%', :search, '%'))
                )
                OR EXISTS (
                    SELECT identity.id
                    FROM UserDiscord identity
                    WHERE identity.user = u
                      AND (
                          LOWER(COALESCE(identity.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(COALESCE(identity.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                )
            )
            """,
        countQuery = """
            SELECT COUNT(u)
            FROM User u
            WHERE EXISTS (
                SELECT membership.id
                FROM UserCommunityStatus membership
                WHERE membership.user = u
                  AND membership.community.id = :communityId
                  AND membership.approved = true
                  AND membership.banned = false
                  AND membership.isPresent = true
            )
            AND (
                :search IS NULL
                OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR EXISTS (
                    SELECT matchingMembership.id
                    FROM UserCommunityStatus matchingMembership
                    WHERE matchingMembership.user = u
                      AND matchingMembership.community.id = :communityId
                      AND LOWER(COALESCE(matchingMembership.displayName, ''))
                          LIKE LOWER(CONCAT('%', :search, '%'))
                )
                OR EXISTS (
                    SELECT identity.id
                    FROM UserDiscord identity
                    WHERE identity.user = u
                      AND (
                          LOWER(COALESCE(identity.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(COALESCE(identity.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                )
            )
            """
    )
    Page<User> searchEligibleCommunityTeamCandidates(
        @Param("communityId") Integer communityId,
        @Param("search") String search,
        Pageable pageable
    );

}
