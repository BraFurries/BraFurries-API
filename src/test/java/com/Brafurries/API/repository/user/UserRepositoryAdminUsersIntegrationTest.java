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
