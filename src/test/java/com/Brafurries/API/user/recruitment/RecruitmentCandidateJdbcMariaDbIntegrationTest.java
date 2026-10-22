package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@EnabledIfEnvironmentVariable(named = "BRF_RECRUITMENT_JDBC_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    RecruitmentDraftRepository.class,
    RecruitmentCandidateRepository.class,
    RecruitmentPublicationRepository.class
})
class RecruitmentCandidateJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_candidate_disposable")
        .withUsername("brf_synthetic_candidate")
        .withPassword("brf_synthetic_candidate_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDraftRepository drafts;
    @Autowired RecruitmentCandidateRepository candidates;
    @Autowired RecruitmentPublicationRepository publication;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES (981001,'applicant'),(981002,'reviewer')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'Tenant A',981002),(981002,'Tenant B',981002)");
        jdbc.update("INSERT INTO user_community_status "
            + "(community_id,user_id,member_since,is_present,banned) "
            + "VALUES (981001,981001,UTC_TIMESTAMP(),1,0)");
    }

    private long published() {
        long process = drafts.create(981001, 981002, "Moderation", null);
        var stage = drafts.appendDraftStage(
            981001,process,"Inscrição","Inscrição",
            RecruitmentDraftDtos.StageType.APPLICATION,0L
        );
        jdbc.update("UPDATE recruitment_stages SET candidate_form_json=? "
            + "WHERE community_id=981001 AND id=?",
            "{\"questions\":[{\"id\":\"why\",\"label\":\"Por quê?\","
                + "\"type\":\"SHORT_TEXT\",\"required\":true}]}",stage.id());
        jdbc.update("UPDATE recruitment_process_versions SET "
            + "eligibility_rules_json=?, terms_json=? WHERE community_id=981001 AND process_id=?",
            "{\"minMembershipDays\":0,\"requireVip\":false,\"requirePartner\":false}",
            "{\"text\":\"Aceito todos os termos da seleção da BraFurries.\\"}",process);
        long version = publication.drafts(981001,process).get(0).id();
        assertTrue(publication.publish(981001,process,version,1L));
        return process;
    }

    @Test
    void applicationIsTenantScopedUniqueAndRecordsAnswersAndConsent() {
        long process = published();
        assertTrue(candidates.listOpen(981001).isEmpty());
        assertNull(candidates.publishedSelection(981001,process,true,false));
        assertTrue(candidates.openEnrollment(981001,process,
            LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(2),2L));
        assertEquals(1,candidates.listOpen(981001).size());
        assertTrue(candidates.listOpen(981002).isEmpty());

        var selection = candidates.publishedSelection(981001,process,true,false);
        assertNotNull(selection);
        assertEquals("APPLICATION", jdbc.queryForObject(
            "SELECT stage_type FROM recruitment_stages WHERE id=?",
            String.class,selection.firstStageId()));
        var stageIds = candidates.stageIds(981001,process,selection.versionId());
        var result = candidates.submit(
            981001,process,selection.versionId(),981001,
            selection.firstStageId(),stageIds,"{\"why\":\"Quero ajudar\"}","a".repeat(64)
        );
        assertNotNull(result.applicationId());
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_application_answers "
                + "WHERE community_id=? AND application_id=?",
            Integer.class,981001,result.applicationId()));
        assertEquals("AWAITING_REVIEW",jdbc.queryForObject(
            "SELECT status FROM recruitment_application_stages "
                + "WHERE community_id=? AND application_id=? AND stage_id=?",
            String.class,981001,result.applicationId(),selection.firstStageId()));
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_application_answers WHERE community_id=981002",
            Integer.class));
        assertThrows(DataIntegrityViolationException.class,()->candidates.submit(
            981001,process,selection.versionId(),981001,
            selection.firstStageId(),stageIds,"{}","a".repeat(64)));
    }

    @Test
    void cannotOpenIntakeAcrossTenantOrWithStaleRevision() {
        long process = published();
        LocalDateTime deadline = LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(2);
        assertFalse(candidates.openEnrollment(981002,process,deadline,2L));
        assertFalse(candidates.openEnrollment(981001,process,deadline,0L));
        assertTrue(candidates.openEnrollment(981001,process,deadline,2L));
        assertFalse(candidates.openEnrollment(981001,process,deadline,2L));
        assertNull(candidates.publishedSelection(981002,process,false,false));
    }
}
