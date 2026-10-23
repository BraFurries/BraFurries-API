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

/** Strict real-MariaDB 10.6 constraints, never shared/prod DB. */
@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({RecruitmentStageVoteRepository.class,RecruitmentStageDecisionRepository.class})
class RecruitmentStageVotingJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database=new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_votes_disposable")
        .withUsername("brf_votes_synthetic")
        .withPassword("brf_votes_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentStageVoteRepository ballots;
    @Autowired RecruitmentStageDecisionRepository decisions;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES "
            + "(981001,'candidate'),(981002,'reviewer'),(981003,'other'),(981004,'tenant-b')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'A',981002),(981002,'B',981004)");
        jdbc.update("INSERT INTO user_community_status "
            + "(community_id,user_id,member_since,is_present,banned) VALUES "
            + "(981001,981001,UTC_TIMESTAMP(),1,0),"
            + "(981001,981002,UTC_TIMESTAMP(),1,0),"
            + "(981001,981003,UTC_TIMESTAMP(),1,0),"
            + "(981002,981004,UTC_TIMESTAMP(),1,0)");
        jdbc.update("INSERT INTO recruitment_processes "
            + "(id,community_id,title,created_by_user_id,status) "
            + "VALUES (982001,981001,'A Recruitment',981002,'ACTIVE'),"
            + "(982002,981002,'B Recruitment',981004,'ACTIVE')");
        jdbc.update("INSERT INTO recruitment_process_versions "
            + "(id,community_id,process_id,version_number,status,created_by_user_id) "
            + "VALUES (983001,981001,982001,1,'PUBLISHED',981002),"
            + "(983002,981002,982002,1,'PUBLISHED',981004)");
        jdbc.update("INSERT INTO recruitment_stages "
            + "(id,community_id,process_id,process_version_id,position,"
            + "internal_name,public_name,stage_type) VALUES "
            + "(984001,981001,982001,983001,1,'Evaluation','Evaluation','HUMAN_EVALUATION'),"
            + "(984002,981002,982002,983002,1,'Other','Other','HUMAN_EVALUATION')");
        jdbc.update("INSERT INTO recruitment_applications "
            + "(id,community_id,process_id,process_version_id,candidate_user_id) VALUES "
            + "(985001,981001,982001,983001,981001),"
            + "(985002,981002,982002,983002,981004)");
        jdbc.update("INSERT INTO recruitment_application_stages "
            + "(community_id,application_id,process_version_id,stage_id,status) VALUES "
            + "(981001,985001,983001,984001,'AWAITING_REVIEW'),"
            + "(981002,985002,983002,984002,'AWAITING_REVIEW')");
        jdbc.update("INSERT INTO recruitment_stage_assessments "
            + "(community_id,application_id,stage_id,evaluator_user_id,opinion) VALUES "
            + "(981001,985001,984001,981002,'Good'),"
            + "(981001,985001,984001,981003,'Acceptable')");
    }

    @Test void immutableBallotRequiresExactCommunityAndSubmittedAssessment() {
        assertEquals(0,ballots.tally(981001,985001,984001).count());
        assertThrows(DataIntegrityViolationException.class,()->
            ballots.cast(981001,985001,984001,981001,"APPROVE"));
        assertThrows(DataIntegrityViolationException.class,()->
            ballots.cast(981002,985001,984001,981002,"APPROVE"));
        long id=ballots.cast(981001,985001,984001,981002,"APPROVE");
        assertTrue(id>0);
        assertThrows(DataIntegrityViolationException.class,()->
            ballots.cast(981001,985001,984001,981002,"REJECT"));
        assertEquals("APPROVE",ballots.own(981001,985001,984001,981002).choice());
        assertNull(ballots.own(981002,985001,984001,981002));
        assertEquals(1,ballots.tally(981001,985001,984001).approves());
        assertEquals(0,ballots.tally(981002,985001,984001).count());
        assertEquals(0,ballots.tally(981001,985002,984002).count());
    }

    @Test void tallyAndVoteDecisionSourcePersistWithoutPublishing() {
        ballots.cast(981001,985001,984001,981002,"APPROVE");
        ballots.cast(981001,985001,984001,981003,"REJECT");
        assertTrue(ballots.tally(981001,985001,984001).tied());
        assertEquals(2,ballots.tally(981001,985001,984001).count());
        decisions.insertVoteDecision(981001,985001,984001,981002,
            "REJECT","Empate pela política configurada","VOTE_TIE_REJECT");
        assertEquals("VOTE_TIE_REJECT",jdbc.queryForObject(
            "SELECT decision_source FROM recruitment_stage_decisions "
                + "WHERE community_id=981001 AND application_id=985001 AND stage_id=984001",
            String.class));
        assertThrows(DataIntegrityViolationException.class,()->
            decisions.insertVoteDecision(981001,985001,984001,981002,
                "REJECT","Repetido","VOTE_MAJORITY"));
    }
}
