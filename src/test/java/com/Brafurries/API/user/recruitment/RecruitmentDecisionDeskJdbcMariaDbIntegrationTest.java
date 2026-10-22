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

/** Runs against disposable MariaDB, not any shared datasource. */
@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentDecisionDeskRepository.class)
class RecruitmentDecisionDeskJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database=new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_desk_disposable")
        .withUsername("brf_desk_synthetic")
        .withPassword("brf_desk_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDecisionDeskRepository desk;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES "
            + "(981001,'applicant'),(981002,'manager'),"
            + "(981003,'reviewer'),(981004,'tenant-b')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'Tenant A',981002),(981002,'Tenant B',981004)");
        jdbc.update("INSERT INTO user_community_status "
            + "(community_id,user_id,member_since,is_present,banned) VALUES "
            + "(981001,981001,UTC_TIMESTAMP(),1,0),"
            + "(981001,981002,UTC_TIMESTAMP(),1,0),"
            + "(981001,981003,UTC_TIMESTAMP(),1,0),"
            + "(981002,981004,UTC_TIMESTAMP(),1,0)");
        jdbc.update("INSERT INTO recruitment_processes "
            + "(id,community_id,title,created_by_user_id,status) VALUES "
            + "(982001,981001,'A staff',981002,'ACTIVE'),"
            + "(982002,981002,'B staff',981004,'ACTIVE')");
        jdbc.update("INSERT INTO recruitment_process_versions "
            + "(id,community_id,process_id,version_number,status,created_by_user_id) "
            + "VALUES (983001,981001,982001,1,'PUBLISHED',981002),"
            + "(983002,981002,982002,1,'PUBLISHED',981004)");
        jdbc.update("INSERT INTO recruitment_stages "
            + "(id,community_id,process_id,process_version_id,position,"
            + "internal_name,public_name,stage_type,evaluation_config_json) VALUES "
            + "(984001,981001,982001,983001,1,'Review','Analysis','APPLICATION',"
            + "'{\"reviewMode\":\"COLLECTIVE\",\"decisionMode\":\"MANUAL\",\"minimumAssessments\":2}'),"
            + "(984002,981001,982001,983001,2,'Interview','Interview','INTERVIEW',NULL),"
            + "(984003,981002,982002,983002,1,'B Review','B Review','APPLICATION',NULL)");
        jdbc.update("INSERT INTO recruitment_applications "
            + "(id,community_id,process_id,process_version_id,candidate_user_id) VALUES "
            + "(985001,981001,982001,983001,981001),"
            + "(985002,981001,982001,983001,981002),"
            + "(985003,981002,982002,983002,981004)");
        jdbc.update("INSERT INTO recruitment_application_stages "
            + "(community_id,application_id,process_version_id,stage_id,status) VALUES "
            + "(981001,985001,983001,984001,'AWAITING_REVIEW'),"
            + "(981001,985001,983001,984002,'PENDING'),"
            + "(981001,985002,983001,984001,'AWAITING_REVIEW'),"
            + "(981001,985002,983001,984002,'PENDING'),"
            + "(981002,985003,983002,984003,'AWAITING_REVIEW')");
        jdbc.update("INSERT INTO recruitment_application_answers "
            + "(community_id,application_id,stage_id,answers_json) VALUES "
            + "(981001,985001,984001,'{\"why\":\"help\"}')");
        jdbc.update("INSERT INTO recruitment_stage_assessments "
            + "(community_id,application_id,stage_id,evaluator_user_id,score,opinion,staff_answers_json)"
            + " VALUES (981001,985001,984001,981002,80.00,'Yes','{\"fit\":\"good\"}'),"
            + "(981001,985001,984001,981003,90.00,'Strong','{\"fit\":\"great\"}')");
    }

    @Test void managerQueueIsScopedByCommunityProcessAndSelfCandidate() {
        var rows=desk.queue(981001,982001L,981002,null);
        assertEquals(1,rows.size());
        assertEquals(985001L,rows.get(0).id());
        assertEquals(981001,rows.get(0).candidateUserId());
        assertTrue(desk.queue(981002,982001L,981002,null).isEmpty());
        assertTrue(desk.queue(981001,982002L,981002,null).isEmpty());
        assertTrue(desk.queue(981001,982001L,981002,985001L).isEmpty());
    }

    @Test void detailsNeverCrossTenantProcessOrCandidateVersion() {
        assertNotNull(desk.application(981001,982001L,985001L));
        assertNull(desk.application(981002,982001L,985001L));
        assertNull(desk.application(981001,982002L,985001L));
        assertEquals("AWAITING_REVIEW",desk.stages(981001,985001L,983001L)
            .get(0).internalStatus());
        assertEquals(2,desk.stages(981001,985001L,983001L)
            .get(0).assessmentCount());
        assertEquals(0,desk.stages(981002,985001L,983001L).size());
        assertEquals("{\"why\":\"help\"}",
            desk.applicationAnswers(981001,985001L,983001L));
        assertNull(desk.applicationAnswers(981002,985001L,983001L));
    }

    @Test void onlyActiveStageExposesItsOwnReviewerEvidence() {
        var rows=desk.evidence(981001,985001L,983001L,984001L);
        assertEquals(2,rows.size());
        assertEquals(981002,rows.get(0).evaluatorUserId());
        assertEquals("Strong",rows.get(1).opinion());
        assertEquals(0,desk.evidence(981001,985001L,983001L,984002L).size());
        assertEquals(0,desk.evidence(981002,985001L,983001L,984001L).size());
        assertEquals(0,desk.evidence(981001,985001L,983002L,984001L).size());
    }
}
