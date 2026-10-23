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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Disposable MariaDB checks real composite FKs and sequential status transitions. */
@EnabledIfEnvironmentVariable(named = "BRF_RECRUITMENT_JDBC_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentStageDecisionRepository.class)
class RecruitmentStageDecisionJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_decisions_disposable")
        .withUsername("brf_decisions_synthetic")
        .withPassword("brf_decisions_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentStageDecisionRepository decisions;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES "
            + "(981001,'candidate'),(981002,'manager'),(981003,'outsider')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'Community A',981002),(981002,'Community B',981003)");
        jdbc.update("INSERT INTO user_community_status "
            + "(community_id,user_id,member_since,is_present,banned) VALUES "
            + "(981001,981001,UTC_TIMESTAMP(),1,0),"
            + "(981001,981002,UTC_TIMESTAMP(),1,0),"
            + "(981002,981003,UTC_TIMESTAMP(),1,0)");
        jdbc.update("INSERT INTO recruitment_processes "
            + "(id,community_id,title,created_by_user_id,status) "
            + "VALUES (982001,981001,'Selection',981002,'ACTIVE')");
        jdbc.update("INSERT INTO recruitment_process_versions "
            + "(id,community_id,process_id,version_number,status,created_by_user_id) "
            + "VALUES (983001,981001,982001,1,'PUBLISHED',981002)");
        jdbc.update("INSERT INTO recruitment_stages "
            + "(id,community_id,process_id,process_version_id,position,"
            + "internal_name,public_name,stage_type) VALUES "
            + "(984001,981001,982001,983001,1,'Application','Application','APPLICATION'),"
            + "(984002,981001,982001,983001,2,'Interview','Interview','INTERVIEW')");
        jdbc.update("INSERT INTO recruitment_applications "
            + "(id,community_id,process_id,process_version_id,candidate_user_id) "
            + "VALUES (985001,981001,982001,983001,981001)");
        jdbc.update("INSERT INTO recruitment_application_stages "
            + "(community_id,application_id,process_version_id,stage_id,status) VALUES "
            + "(981001,985001,983001,984001,'AWAITING_REVIEW'),"
            + "(981001,985001,983001,984002,'PENDING')");
        jdbc.update("INSERT INTO recruitment_stage_assessments "
            + "(community_id,application_id,stage_id,evaluator_user_id,opinion) "
            + "VALUES (981001,985001,984001,981002,'Reviewed')");
    }

    @Test void decisionsCannotCrossCommunityNorRepeat() {
        assertNull(decisions.lockApplication(981002,985001L));
        assertTrue(decisions.stages(981002,985001L,983001L).isEmpty());
        assertEquals(1,decisions.assessmentCount(981001,985001,984001));
        assertEquals(0,decisions.assessmentCount(981002,985001,984001));
        assertThrows(DataIntegrityViolationException.class, () ->
            decisions.insertDecision(981002,985001,984001,981003,"APPROVE",null));
    }

    @Test void oneImmutableDecisionAndExactlyOneStageAreAdvanced() {
        assertNotNull(decisions.lockApplication(981001,985001L));
        var rows = decisions.stages(981001,985001,983001);
        assertEquals(2,rows.size());
        assertEquals("PENDING",rows.get(1).status());
        assertEquals("APPLICATION",rows.get(0).type());
        long id = decisions.insertDecision(981001,985001,984001,981002,"APPROVE",null);
        assertTrue(id > 0);
        assertEquals("APPROVE",decisions.decision(981001,985001,984001).outcome());
        assertNull(decisions.decision(981002,985001,984001));
        assertTrue(decisions.complete(981001,985001,984001));
        assertFalse(decisions.complete(981001,985001,984001));
        assertTrue(decisions.advance(981001,985001,984002,"AWAITING_REVIEW"));
        assertFalse(decisions.advance(981001,985001,984002,"AWAITING_REVIEW"));
        assertEquals("AWAITING_REVIEW",decisions.stages(981001,985001,983001).get(1).status());
        assertThrows(DataIntegrityViolationException.class, () ->
            decisions.insertDecision(981001,985001,984001,981002,"APPROVE",null));
    }

    @Test void onlyMemberOfCommunityMayDecideAndRejectionNeedsReason() {
        assertThrows(DataIntegrityViolationException.class, () ->
            decisions.insertDecision(981001,985001,984001,981003,"APPROVE",null));
    }
}
