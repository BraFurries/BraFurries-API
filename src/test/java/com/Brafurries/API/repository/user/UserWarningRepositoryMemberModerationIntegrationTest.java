package com.Brafurries.API.repository.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserWarningRepositoryMemberModerationIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

    @Autowired UserRepository users;
    @Autowired CommunityRepository communities;
    @Autowired UserWarningRepository warnings;
    @Autowired EntityManager entityManager;

    @Test
    void returnsOnlyTheRequestedUsersCommunityRecordsWithMariaDbNativeTypesAndPaging() {
        User member = user("member@example.com");
        User otherMember = user("other@example.com");
        Community target = community("Target");
        Community otherCommunity = community("Other");
        LocalDate today = LocalDate.now();

        // The production schema can contain legacy NULL can_appeal values even though the current entity marks it non-null.
        entityManager.createNativeQuery("ALTER TABLE user_bans MODIFY can_appeal BIT NULL").executeUpdate();

        warning(member, target, today.minusDays(5), "active warning", false);
        warning(member, target, today.minusDays(4), "expired warning", true);
        ban(member, target, today.minusDays(3), "active ban", 1, null, null, null);
        ban(member, target, today.minusDays(2), "expired ban", 0, today.minusDays(1), null, null);
        ban(member, target, today.minusDays(1), "revoked ban", null, null, LocalDateTime.now(), "reviewed");
        warning(otherMember, target, today, "other user", false);
        ban(member, otherCommunity, today, "other community", 1, null, null, null);
        entityManager.flush();
        entityManager.clear();

        var all = warnings.findMemberCommunityModerationRecords(member.getId(), target.getId(), today, PageRequest.of(0, 10));

        assertEquals(5, all.getTotalElements());
        assertEquals(5, all.getContent().size());
        assertEquals("revoked ban", all.getContent().get(0).getReason());
        assertEquals("revoked", all.getContent().get(0).getStatus());
        assertNull(all.getContent().get(0).getCanAppeal());
        assertEquals("reviewed", all.getContent().get(0).getRevocationReason());
        assertTrue(all.getContent().get(0).getRevokedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertEquals("expired ban", all.getContent().get(1).getReason());
        assertEquals("expired", all.getContent().get(1).getStatus());
        assertFalse(all.getContent().get(1).getCanAppeal().intValue() != 0);
        assertEquals("active ban", all.getContent().get(2).getReason());
        assertEquals("active", all.getContent().get(2).getStatus());
        assertTrue(all.getContent().get(2).getCanAppeal().intValue() != 0);
        assertEquals("expired warning", all.getContent().get(3).getReason());
        assertEquals("expired", all.getContent().get(3).getStatus());
        assertEquals("active warning", all.getContent().get(4).getReason());
        assertEquals("active", all.getContent().get(4).getStatus());

        var secondPage = warnings.findMemberCommunityModerationRecords(member.getId(), target.getId(), today, PageRequest.of(1, 2));
        assertEquals(5, secondPage.getTotalElements());
        assertEquals(3, secondPage.getTotalPages());
        assertEquals(2, secondPage.getContent().size());
        assertEquals("active ban", secondPage.getContent().get(0).getReason());
        assertEquals("expired warning", secondPage.getContent().get(1).getReason());
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(email);
        return users.saveAndFlush(user);
    }

    private Community community(String name) {
        Community community = new Community();
        community.setName(name);
        return communities.saveAndFlush(community);
    }

    private void warning(User user, Community community, LocalDate date, String reason, boolean expired) {
        entityManager.createNativeQuery("""
                INSERT INTO user_warnings (user_id, community_id, date, reason, expired, applied_by)
                VALUES (:userId, :communityId, :date, :reason, :expired, :appliedBy)
                """)
            .setParameter("userId", user.getId())
            .setParameter("communityId", community.getId())
            .setParameter("date", date)
            .setParameter("reason", reason)
            .setParameter("expired", expired ? 1 : 0)
            .setParameter("appliedBy", user.getId())
            .executeUpdate();
    }

    private void ban(
        User user, Community community, LocalDate date, String reason, Integer canAppeal,
        LocalDate validUntil, LocalDateTime revokedAt, String revocationReason
    ) {
        entityManager.createNativeQuery("""
                INSERT INTO user_bans (user_id, community_id, date, reason, can_appeal, valid_until, registered_at, revoked_at, revocation_reason)
                VALUES (:userId, :communityId, :date, :reason, :canAppeal, :validUntil, :registeredAt, :revokedAt, :revocationReason)
                """)
            .setParameter("userId", user.getId())
            .setParameter("communityId", community.getId())
            .setParameter("date", date)
            .setParameter("reason", reason)
            .setParameter("canAppeal", canAppeal)
            .setParameter("validUntil", validUntil)
            .setParameter("registeredAt", LocalDateTime.now())
            .setParameter("revokedAt", revokedAt)
            .setParameter("revocationReason", revocationReason)
            .executeUpdate();
    }
}
