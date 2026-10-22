package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real MariaDB FKs and reviewer scope; no production datasource involved. */
@EnabledIfEnvironmentVariable(named = "BRF_RECRUITMENT_JDBC_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentHumanReviewRepository.class)
class RecruitmentHumanReviewJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_human_review_disposable")
        .withUsername("brf_review_synthetic")
        .withPassword("brf_review_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @TestConfiguration
    static class JacksonConfig {
        @Bean ObjectMapper reviewerMapper() { return new ObjectMapper(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentHumanReviewRepository repository;

    @BeforeEach void seed() {
        jdbc.update(
            "INSERT INTO users (id,username) VALUES "
                + "(981001,'candidate'),(981002,'reviewer'),(981003,'other')"
        );
        jdbc.update(
            "INSERT INTO communities (id,name,owner_user_id) VALUES "
                + "(981001,'Community A',981002),(981002,'Community B',981003)"
        );
        jdbc.update(
            "INSERT INTO user_community_status "
                + "(community_id,user_id,member_since,is_present,banned) VALUES "
                + "(981001,981001,UTC_TIMESTAMP(),1,0),"
                + "(981001,981002,UTC_TIMESTAMP(),1,0),"
                + "(981002,981003,UTC_TIMESTAMP(),1,0)"
        );
        jdbc.update(
            "INSERT INTO recruitment_processes "
                + "(id,community_id,title,created_by_user_id,status) "
                + "VALUES (982001,981001,'Test Selection',981002,'ACTIVE')"
        );
        jdbc.update(
            "INSERT INTO recruitment_process_versions "
                + "(id,community_id,process_id,version_number,status,created_by_user_id) "
                + "VALUES (983001,981001,982001,1,'PUBLISHED',981002)"
        );
        jdbc.update(
            "INSERT INTO recruitment_stages "
                + "(id,community_id,process_id,process_version_id,position,"
                + "internal_name,public_name,stage_type) "
                + "VALUES (984001,981001,982001,983001,1,'Evaluation','Evaluation',"
                + "'HUMAN_EVALUATION')"
        );
        jdbc.update(
            "INSERT INTO recruitment_applications "
                + "(id,community_id,process_id,process_version_id,candidate_user_id) "
                + "VALUES (985001,981001,982001,983001,981001)"
        );
        jdbc.update(
            "INSERT INTO recruitment_application_stages "
                + "(community_id,application_id,process_version_id,stage_id,status) "
                + "VALUES (981001,985001,983001,984001,'AWAITING_REVIEW')"
        );
    }

    @Test void designationIsRequiredAndCannotCrossCommunity() {
        assertFalse(repository.assignedToStage(981001,984001,983001,981002));
        assertTrue(repository.eligiblePerson(981001,981002));
        assertFalse(repository.eligiblePerson(981001,981003));
        assertFalse(repository.eligiblePerson(981002,981002));
        var stage = repository.stage(981001,984001L);
        assertNotNull(stage);
        assertNull(repository.stage(981002,984001L));
        long id = repository.assign(981001,stage,981002,null,981002);
        assertTrue(id > 0);
        assertTrue(repository.assignedToStage(981001,984001,983001,981002));
        assertFalse(repository.assignedToStage(981001,984001,983001,981003));
        assertFalse(repository.assignedToStage(981001,984001,983999,981002));
        assertEquals(1, repository.assignments(981001,984001).size());
        assertTrue(repository.remove(981001,984001,id));
        assertFalse(repository.assignedToStage(981001,984001,983001,981002));
    }

    @Test void humanAssessmentsAreIndividualAndUniqueForStage() {
        var target = repository.lockAssessmentTarget(981001,985001L,984001L);
        assertNotNull(target);
        assertEquals("AWAITING_REVIEW",target.stageStatus());
        assertEquals(981001,target.candidateUserId());
        assertNull(repository.lockAssessmentTarget(981002,985001L,984001L));
        long id = repository.submit(
            981001,985001,984001,981002,new BigDecimal("81.50"),
            "Aprovado na análise", "{\"communication\":\"clear\"}"
        );
        assertTrue(id > 0);
        var own = repository.ownAssessment(981001,985001,984001,981002);
        assertNotNull(own);
        assertEquals(new BigDecimal("81.50"), own.score());
        assertEquals("clear",own.staffAnswers().get("communication").asText());
        assertNull(repository.ownAssessment(981001,985001,984001,981003));
        assertNull(repository.ownAssessment(981002,985001,984001,981002));
        assertThrows(DataIntegrityViolationException.class, () ->
            repository.submit(981001,985001,984001,981002,null,"Repeat",null)
        );
    }
}
