package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Opt-in coverage for the assignment native upsert against a disposable MariaDB
 * whose schema already includes Database PRs #16 and #17.
 */
@EnabledIfEnvironmentVariable(named = "BRF_COMMUNITY_TEAM_ASSIGNMENTS_INTEGRATION", matches = "true")
@DataJpaTest(properties = {
    "spring.datasource.url=${BRF_DISPOSABLE_JDBC_URL}",
    "spring.datasource.username=${BRF_DISPOSABLE_DB_USER}",
    "spring.datasource.password=${BRF_DISPOSABLE_DB_PASSWORD}",
    "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommunityTeamAssignmentJpaMariaDbIntegrationTest {

    @Autowired private CommunityTeamRoleRepository roleRepository;
    @Autowired private CommunityTeamRoleAssignmentRepository assignmentRepository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void nativeUpsertIsIdempotentAndPreservesCreatedAt() {
        Community community = new Community();
        community.setName("Assignment integration");
        entityManager.persistAndFlush(community);

        User user = new User();
        user.setDisplayName("Integration member");
        entityManager.persistAndFlush(user);

        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.of(2026, 9, 30, 12, 0));
        membership.setApproved(true);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBanned(false);
        membership.setIsPresent(true);
        membership.setBirthdayMentionable(false);
        entityManager.persistAndFlush(membership);

        CommunityTeamRole role = new CommunityTeamRole();
        role.setCommunity(community);
        role.setName("Moderador");
        role.setActive(true);
        roleRepository.saveAndFlush(role);

        assignmentRepository.insertIfAbsent(community.getId(), role.getId(), user.getId());
        CommunityTeamRoleAssignment first = assignmentRepository
            .findByCommunityIdAndRoleIdAndUserId(community.getId(), role.getId(), user.getId())
            .orElseThrow();

        assertNotNull(first.getCreatedAt());

        LocalDateTime createdAt = first.getCreatedAt();
        entityManager.clear();

        assignmentRepository.insertIfAbsent(community.getId(), role.getId(), user.getId());
        CommunityTeamRoleAssignment second = assignmentRepository
            .findByCommunityIdAndRoleIdAndUserId(community.getId(), role.getId(), user.getId())
            .orElseThrow();

        assertEquals(createdAt, second.getCreatedAt());
        assertEquals(
            1,
            assignmentRepository
                .findByCommunityIdAndRoleIdOrderByUserIdAsc(community.getId(), role.getId())
                .size()
        );
    }
}
