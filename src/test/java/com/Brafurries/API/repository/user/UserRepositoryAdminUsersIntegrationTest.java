package com.Brafurries.API.repository.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserRepositoryAdminUsersIntegrationTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

    @Autowired UserRepository users;
    @Autowired UserBanRepository bans;
    @Autowired UserDiscordRepository discord;
    @Autowired CommunityRepository communities;
    @Autowired EntityManager entityManager;

    @Test
    void filtersRoleAndStatusBeforePaginationUsingMariaDb() {
        int adminRoleId = role("admin");
        int moderatorRoleId = role("moderator");

        User admin = user("Nick Admin");
        assignRole(admin, adminRoleId);
        assignRole(admin, moderatorRoleId);

        User moderator = user("Nick Moderator");
        assignRole(moderator, moderatorRoleId);

        User bannedMember = user("Nick Banned");
        ban(bannedMember);

        var admins = users.searchAdminUsersFiltered(
            "Nick", null, "active", "admin", LocalDate.now(), PageRequest.of(0, 1)
        );
        assertEquals(1, admins.getTotalElements());
        assertEquals(admin.getId(), admins.getContent().getFirst().getId());

        var moderators = users.searchAdminUsersFiltered(
            "Nick", null, "active", "moderator", LocalDate.now(), PageRequest.of(0, 1)
        );
        assertEquals(1, moderators.getTotalElements());
        assertEquals(moderator.getId(), moderators.getContent().getFirst().getId());

        var banned = users.searchAdminUsersFiltered(
            "Nick", null, "banned", null, LocalDate.now(), PageRequest.of(0, 1)
        );
        assertEquals(1, banned.getTotalElements());
        assertEquals(bannedMember.getId(), banned.getContent().getFirst().getId());
    }

    @Test
    void returnsFilteredTotalsAndPagesFromTheDatabaseQuery() {
        int adminRoleId = role("admin");

        User first = user("Searchable One");
        assignRole(first, adminRoleId);

        User second = user("Searchable Two");
        assignRole(second, adminRoleId);

        user("Searchable Member");

        var firstPage = users.searchAdminUsersFiltered(
            "Searchable", null, "active", "admin", LocalDate.now(), PageRequest.of(0, 1)
        );

        assertEquals(2, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(1, firstPage.getContent().size());
    }

    @Test
    void findsUserByExactPersistedDiscordSnowflake() {
        User matched = user("Discord Match");
        UserDiscord account = new UserDiscord();
        account.setUser(matched);
        account.setDiscordUserId(123456789012345678L);
        account.setUsername("discord.match");
        discord.saveAndFlush(account);
        user("123456789012345678 is only text");

        var result = users.searchAdminUsersFiltered(
            null, 123456789012345678L, null, null, LocalDate.now(), PageRequest.of(0, 20)
        );

        assertEquals(1, result.getTotalElements());
        assertEquals(matched.getId(), result.getContent().getFirst().getId());
    }

    @Test
    void loadsDiscordAccountOwnerForPostCommitIdentityPropagation() {
        User linked = user("Post Commit Identity");
        UserDiscord account = new UserDiscord();
        account.setUser(linked);
        account.setDiscordUserId(987654321012345678L);
        account.setUsername("post.commit");
        discord.saveAndFlush(account);
        entityManager.clear();

        var accounts = discord.findByUserIdIn(List.of(linked.getId()));

        assertEquals(1, accounts.size());
        assertTrue(
            entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil()
                .isLoaded(accounts.getFirst().getUser())
        );
        assertEquals(linked.getId(), accounts.getFirst().getUser().getId());
    }

    @Test
    void searchesCommunityMembersByPersistedDiscordNamesAndRespectsTenant() {
        Community community = new Community();
        community.setName("BraFurries");
        community = communities.saveAndFlush(community);
        Community otherCommunity = new Community();
        otherCommunity.setName("Other Community");
        otherCommunity = communities.saveAndFlush(otherCommunity);

        // Lifecycle intentionally persists Discord-only identities without filling users.username.
        User byUsername = users.saveAndFlush(new User());
        membership(byUsername, community, null);
        discordIdentity(byUsername, 223456789012345678L, "discord.only", "Member One");

        User byDisplayName = users.saveAndFlush(new User());
        membership(byDisplayName, community, null);
        discordIdentity(byDisplayName, 323456789012345678L, "opaque.username", "discord.visible");

        User foreignUser = users.saveAndFlush(new User());
        membership(foreignUser, otherCommunity, null);
        discordIdentity(foreignUser, 1333333333333333333L, "other_community_only", "Outside");

        var first = users.searchCommunityMembers(
            community.getId(), "DISCORD", PageRequest.of(0, 1, Sort.by("id"))
        );
        assertEquals(2, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(1, first.getContent().size());

        var second = users.searchCommunityMembers(
            community.getId(), "discord", PageRequest.of(1, 1, Sort.by("id"))
        );
        assertEquals(2, second.getTotalElements());
        assertEquals(byDisplayName.getId(), second.getContent().getFirst().getId());

        var foreign = users.searchCommunityMembers(
            community.getId(), "other_community_only", PageRequest.of(0, 20)
        );
        assertEquals(0, foreign.getTotalElements());

        // Team candidate pagination must use the same name predicates as the member list.
        var candidates = users.searchEligibleCommunityTeamCandidates(
            community.getId(), "DISCORD", PageRequest.of(0, 1, Sort.by("id"))
        );
        assertEquals(2, candidates.getTotalElements());
        assertEquals(2, candidates.getTotalPages());
        assertEquals(byUsername.getId(), candidates.getContent().getFirst().getId());

        var outside = users.searchEligibleCommunityTeamCandidates(
            community.getId(), "other_community_only", PageRequest.of(0, 20)
        );
        assertEquals(0, outside.getTotalElements());
    }

    @Test
    void searchesCommunityScopedMemberDisplayNameWithoutChangingIdentity() {
        Community community = new Community();
        community.setName("BraFurries");
        community = communities.saveAndFlush(community);
        User member = users.saveAndFlush(new User());
        membership(member, community, "Café Furry");

        var result = users.searchCommunityMembers(
            community.getId(), "Café", PageRequest.of(0, 20)
        );

        assertEquals(1, result.getTotalElements());
        assertEquals(member.getId(), result.getContent().getFirst().getId());
    }

    @Test
    void searchesDiscordSnowflakeExactlyInsideTheRequestedCommunity() {
        Community community = new Community();
        community.setName("BraFurries");
        community = communities.saveAndFlush(community);
        Community other = new Community();
        other.setName("Other");
        other = communities.saveAndFlush(other);

        User member = users.saveAndFlush(new User());
        membership(member, community, null);
        discordIdentity(member, 223456789012345678L, "visible.user", "Visible");

        User foreign = users.saveAndFlush(new User());
        membership(foreign, other, null);
        discordIdentity(foreign, 323456789012345678L, "foreign.user", "Foreign");

        var found = users.searchCommunityMembersByDiscordId(
            community.getId(), 223456789012345678L,
            PageRequest.of(0, 20, Sort.by("id"))
        );
        assertEquals(1, found.getTotalElements());
        assertEquals(member.getId(), found.getContent().getFirst().getId());

        var denied = users.searchCommunityMembersByDiscordId(
            community.getId(), 323456789012345678L,
            PageRequest.of(0, 20)
        );
        assertEquals(0, denied.getTotalElements());
    }

    @Test
    void preservesRolePrecedenceAndUnknownRoleFiltering() {
        int admin = role("admin");
        int moderator = role("moderator");
        int supporter = role("supporter");
        int custom = role("custom");
        User adminUser = user("Rank Admin");
        assignRole(adminUser, admin);
        assignRole(adminUser, supporter);
        User modUser = user("Rank Moderator");
        assignRole(modUser, moderator);
        assignRole(modUser, supporter);
        User supporterUser = user("Rank Supporter");
        assignRole(supporterUser, supporter);
        User memberUser = user("Rank Member");
        assignRole(memberUser, custom);
        User noRoles = user("Rank No Roles");
        var sorted = PageRequest.of(0, 1, Sort.by("id"));
        var today = LocalDate.now();
        assertEquals(adminUser.getId(), users.searchAdminUsersFiltered("Rank", null, null, "admin", today, sorted).getContent().getFirst().getId());
        assertEquals(modUser.getId(), users.searchAdminUsersFiltered("Rank", null, null, "moderator", today, sorted).getContent().getFirst().getId());
        assertEquals(supporterUser.getId(), users.searchAdminUsersFiltered("Rank", null, null, "supporter", today, sorted).getContent().getFirst().getId());
        var members = users.searchAdminUsersFiltered("Rank", null, null, "member", today, sorted);
        assertEquals(2, members.getTotalElements());
        assertEquals(memberUser.getId(), members.getContent().getFirst().getId());
        assertEquals(noRoles.getId(), users.searchAdminUsersFiltered("Rank", null, null, "member", today,
            PageRequest.of(1, 1, Sort.by("id"))).getContent().getFirst().getId());
        assertEquals(0, users.searchAdminUsersFiltered("Rank", null, null, "unknown", today, sorted).getTotalElements());
    }

    @Test
    void keepsBanExpiryInclusiveAndIgnoresRevokedBansAcrossCommunities() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        Community community = new Community();
        community.setName("Ban scope");
        community = communities.saveAndFlush(community);
        Community otherCommunity = new Community();
        otherCommunity.setName("Other ban scope");
        otherCommunity = communities.saveAndFlush(otherCommunity);
        User expired = user("BanScope Expired");
        User revoked = user("BanScope Revoked");
        User expiresToday = user("BanScope Today");
        User indefinite = user("BanScope Indefinite");
        User unbanned = user("BanScope Unbanned");
        saveBan(expired, community, today.minusDays(1), null, today);
        saveBan(revoked, community, null, today.atStartOfDay(), today);
        saveBan(expiresToday, community, today, null, today);
        saveBan(indefinite, otherCommunity, null, null, today);
        entityManager.flush();
        entityManager.clear();
        var sorted = PageRequest.of(0, 20, Sort.by("id"));
        assertEquals(List.of(expiresToday.getId(), indefinite.getId()),
            users.searchAdminUsersFiltered("BanScope", null, "banned", null, today, sorted)
                .map(User::getId).getContent());
        assertEquals(List.of(expired.getId(), revoked.getId(), unbanned.getId()),
            users.searchAdminUsersFiltered("BanScope", null, "active", null, today, sorted)
                .map(User::getId).getContent());
        assertEquals(0, users.searchAdminUsersFiltered("BanScope", null, "unknown", null, today, sorted).getTotalElements());
        assertEquals(List.of(indefinite.getId()),
            users.searchAdminUsersFiltered("BanScope", null, "banned", null, null, sorted)
                .map(User::getId).getContent());
    }

    @Test
    void exactDiscordIdTakesPrecedenceOverTextButNotRoleOrStatus() {
        User matched = user("Exact match");
        discordIdentity(matched, 723456789012345678L, "exact.match", "Exact");
        var sorted = PageRequest.of(0, 20, Sort.by("id"));
        var today = LocalDate.now();
        assertEquals(List.of(matched.getId()),
            users.searchAdminUsersFiltered("no match", 723456789012345678L, "active", "member", today, sorted)
                .map(User::getId).getContent());
        assertEquals(0, users.searchAdminUsersFiltered("Exact", 723456789012345678L, "banned", null, today, sorted).getTotalElements());
        assertEquals(0, users.searchAdminUsersFiltered("Exact", 723456789012345678L, null, "admin", today, sorted).getTotalElements());
    }

    private void saveBan(User user, Community community, LocalDate validUntil, LocalDateTime revokedAt, LocalDate today) {
        UserBan ban = new UserBan();
        ban.setUser(user);
        ban.setCommunity(community);
        ban.setDate(today.minusDays(5));
        ban.setReason("Integration test");
        ban.setCanAppeal(true);
        ban.setRegisteredAt(today.minusDays(5).atStartOfDay());
        ban.setValidUntil(validUntil);
        ban.setRevokedAt(revokedAt);
        bans.saveAndFlush(ban);
    }

    /** Compare against the exact pre-refactor JPQL, retained only as a test oracle. */
    @Test
    void criteriaMatchesLegacyJpqlAcrossFiltersAndPages() {
        int admin = role("admin");
        int moderator = role("moderator");
        int supporter = role("supporter");
        User first = user("Parity Alpha");
        assignRole(first, admin);
        assignRole(first, moderator);
        User second = user("Parity Beta");
        assignRole(second, supporter);
        User third = user("Parity O'Neil");
        User fourth = users.saveAndFlush(new User());
        discordIdentity(fourth, 823456789012345678L, "parity.discord", "Parity Visible");
        discordIdentity(fourth, 923456789012345678L, "parity.alt", "Other");
        ban(second);
        entityManager.flush();
        entityManager.clear();
        LocalDate today = LocalDate.now();

        record Filter(String text, Long discordId, String status, String role) {}
        var filters = List.of(
            new Filter(null, null, null, null),
            new Filter("PARITY", null, null, null),
            new Filter("Parity", null, "active", "admin"),
            new Filter("Parity", null, "banned", "supporter"),
            new Filter("Parity", null, "active", "member"),
            new Filter("Parity", null, null, "moderator"),
            new Filter("Parity", null, null, "unknown"),
            new Filter("Parity", null, "unknown", null),
            new Filter("Parity", null, "ACTIVE", "MEMBER"),
            new Filter("O'Neil", null, null, null),
            new Filter("Parity%", null, null, null),
            new Filter("Parity _", null, null, null),
            new Filter("does not match", 823456789012345678L, "active", "member"),
            new Filter(null, 823456789012345678L, "banned", null)
        );
        for (var filter : filters) {
            var expected = entityManager.createQuery(LEGACY_ADMIN_SEARCH + " ORDER BY u.id", User.class)
                .setParameter("search", filter.text())
                .setParameter("discordUserId", filter.discordId())
                .setParameter("status", filter.status())
                .setParameter("role", filter.role())
                .setParameter("today", today)
                .getResultList().stream().map(User::getId).toList();
            for (int page = 0; page <= 1; page++) {
                var actual = users.searchAdminUsersFiltered(filter.text(), filter.discordId(),
                    filter.status(), filter.role(), today, PageRequest.of(page, 2, Sort.by("id")));
                assertEquals(expected.size(), actual.getTotalElements(), filter.toString());
                assertEquals(expected.stream().skip(page * 2L).limit(2).toList(),
                    actual.map(User::getId).getContent(), filter.toString());
            }
        }
    }

    private static final String LEGACY_ADMIN_SEARCH = """
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
            """;

    private UserCommunityStatus membership(User user, Community community, String displayName) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setDisplayName(displayName);
        membership.setMemberSince(LocalDateTime.now());
        membership.setApproved(true);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBanned(false);
        membership.setIsPresent(true);
        membership.setBirthdayMentionable(false);
        return entityManager.merge(membership);
    }

    private void discordIdentity(User user, long id, String username, String displayName) {
        UserDiscord identity = new UserDiscord();
        identity.setUser(user);
        identity.setDiscordUserId(id);
        identity.setUsername(username);
        identity.setDisplayName(displayName);
        discord.saveAndFlush(identity);
    }

    private User user(String displayName) {
        User user = new User();
        user.setDisplayName(displayName);
        return users.saveAndFlush(user);
    }

    private int role(String name) {
        entityManager.createNativeQuery("""
            INSERT INTO api_roles (name, description, created_at)
            VALUES (:name, NULL, CURRENT_TIMESTAMP)
            """)
            .setParameter("name", name)
            .executeUpdate();

        return ((Number) entityManager.createNativeQuery("""
            SELECT id FROM api_roles WHERE name = :name
            """)
            .setParameter("name", name)
            .getSingleResult()).intValue();
    }

    private void assignRole(User user, int roleId) {
        entityManager.createNativeQuery("""
            INSERT INTO api_user_roles (user_id, role_id, created_at)
            VALUES (:userId, :roleId, CURRENT_TIMESTAMP)
            """)
            .setParameter("userId", user.getId())
            .setParameter("roleId", roleId)
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private void ban(User user) {
        Community community = new Community();
        community.setName("Test Community");
        community = communities.saveAndFlush(community);

        UserBan ban = new UserBan();
        ban.setUser(user);
        ban.setCommunity(community);
        ban.setDate(LocalDate.now());
        ban.setReason("Integration test");
        ban.setCanAppeal(true);
        ban.setRegisteredAt(LocalDateTime.now());
        bans.saveAndFlush(ban);
    }
}
