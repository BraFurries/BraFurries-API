package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real MariaDB publication safety, with no shared datasource.
 * Baseline + Database #12 + #13 are pinned by CI.
 */
@EnabledIfEnvironmentVariable(named = "BRF_RECRUITMENT_JDBC_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RecruitmentDraftRepository.class, RecruitmentPublicationRepository.class})
class RecruitmentPublicationJdbcMariaDbIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_recruitment_publication")
        .withUsername("brf_recruitment_synthetic")
        .withPassword("brf_recruitment_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDraftRepository drafts;
    @Autowired RecruitmentPublicationRepository publication;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO users (id,username) VALUES (981001,'publisher-actor')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'Tenant A',981001),(981002,'Tenant B',981001)");
    }

    private long createDraft(Integer communityId) {
        long process = drafts.create(communityId, 981001, "Selection", null);
        jdbc.update("UPDATE recruitment_process_versions SET eligibility_rules_json=? "
            + "WHERE community_id=? AND process_id=?",
            "{\"minMembershipDays\":30,\"requireVip\":false,\"requirePartner\":false}",
            communityId, process);
        return process;
    }

    @Test
    void publishedVersionIsFrozenWhileIntakeStaysClosedAndPrivate() {
        long process = createDraft(981001);
        var before = publication.lockProcess(981001, process);
        assertNotNull(before);
        assertEquals("DRAFT", before.status());
        long version = publication.drafts(981001, process).get(0).id();
        assertTrue(publication.publish(981001, process, version, 0L));

        assertEquals("ACTIVE", publication.findProcess(981001, process).status());
        assertEquals(1L, publication.findProcess(981001, process).revision());
        assertTrue(publication.drafts(981001, process).isEmpty());
        String statuses = jdbc.queryForObject(
            "SELECT CONCAT(status,':',visibility,':',enrollment_status) "
                + "FROM recruitment_processes WHERE community_id=? AND id=?",
            String.class, 981001, process
        );
        assertEquals("ACTIVE:PRIVATE:CLOSED", statuses);
        String versionStatus = jdbc.queryForObject(
            "SELECT status FROM recruitment_process_versions "
                + "WHERE community_id=? AND process_id=? AND id=?",
            String.class, 981001, process, version
        );
        assertEquals("PUBLISHED", versionStatus);
        assertFalse(drafts.updateDraft(981001, process, "Illegal", null, 1L));
    }

    @Test
    void staleRevisionCannotOverwriteEligibilityAndDifferentTenantsStayIsolated() {
        long processA = createDraft(981001);
        long processB = createDraft(981002);
        long versionA = publication.drafts(981001, processA).get(0).id();
        long versionB = publication.drafts(981002, processB).get(0).id();
        assertFalse(publication.updateEligibility(981002, processA, versionA, 0L, "{}"));
        assertTrue(publication.updateEligibility(981001, processA, versionA, 0L,
            "{\"minMembershipDays\":90,\"requireVip\":true,\"requirePartner\":false}"));
        assertFalse(publication.updateEligibility(981001, processA, versionA, 0L, "{}"));
        assertEquals(1L, publication.findProcess(981001, processA).revision());
        assertEquals(0L, publication.findProcess(981002, processB).revision());
        assertEquals(0L, publication.findProcess(981001, processB) == null ? 0L : -1L);
        assertEquals(1, publication.drafts(981002, processB).size());
        assertEquals(versionB, publication.drafts(981002, processB).get(0).id());
    }
}
