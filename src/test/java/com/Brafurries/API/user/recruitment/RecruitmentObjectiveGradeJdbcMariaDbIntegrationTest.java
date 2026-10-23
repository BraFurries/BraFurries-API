package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.GradePolicy;
import java.math.BigDecimal;
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

@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({RecruitmentObjectiveGradeRepository.class,RecruitmentCandidateAttemptRepository.class,
    RecruitmentStageDecisionRepository.class})
class RecruitmentObjectiveGradeJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database=new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_grades_disposable")
        .withUsername("brf_grades_synthetic")
        .withPassword("brf_grades_synthetic_only")
        .withInitScript("recruitment_schema.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentCandidateAttemptRepository attempts;
    @Autowired RecruitmentObjectiveGradeRepository grades;
    @Autowired RecruitmentStageDecisionRepository decisions;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES "
            +"(981001,'grade-candidate'),(981002,'grade-admin'),(981003,'other-tenant')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            +"(981001,'Tenant A',981002),(981002,'Tenant B',981003)");
        jdbc.update("INSERT INTO user_community_status "
            +"(community_id,user_id,member_since,is_present,banned) VALUES "
            +"(981001,981001,UTC_TIMESTAMP(),1,0),"
            +"(981001,981002,UTC_TIMESTAMP(),1,0),"
            +"(981002,981003,UTC_TIMESTAMP(),1,0)");
        jdbc.update("INSERT INTO recruitment_processes "
            +"(id,community_id,title,created_by_user_id,status) "
            +"VALUES (982001,981001,'Prova A',981002,'ACTIVE'),"
            +"(982002,981002,'Prova B',981003,'ACTIVE')");
        jdbc.update("INSERT INTO recruitment_process_versions "
            +"(id,community_id,process_id,version_number,status,created_by_user_id) "
            +"VALUES (983001,981001,982001,1,'PUBLISHED',981002),"
            +"(983002,981002,982002,1,'PUBLISHED',981003)");
        jdbc.update("INSERT INTO recruitment_stages "
            +"(id,community_id,process_id,process_version_id,position,"
            +"internal_name,public_name,stage_type,candidate_form_json) "
            +"VALUES (984001,981001,982001,983001,1,'Exam A','Exam A','AUTO_EVALUATION',"
            +"'{\"questions\":[]}'),"
            +"(984002,981002,982002,983002,1,'Exam B','Exam B','AUTO_EVALUATION',"
            +"'{\"questions\":[]}')");
        jdbc.update("INSERT INTO recruitment_applications "
            +"(id,community_id,process_id,process_version_id,candidate_user_id) VALUES "
            +"(985001,981001,982001,983001,981001),"
            +"(985002,981002,982002,983002,981003)");
        jdbc.update("INSERT INTO recruitment_application_stages "
            +"(community_id,application_id,process_version_id,stage_id,status) VALUES "
            +"(981001,985001,983001,984001,'AWAITING_CANDIDATE'),"
            +"(981002,985002,983002,984002,'AWAITING_CANDIDATE')");
        jdbc.update("INSERT INTO recruitment_stage_attempt_policies "
            +"(community_id,process_version_id,stage_id,max_attempts,time_limit_seconds,"
            +"passing_percentage,result_strategy) "
            +"VALUES (981001,983001,984001,2,1800,70.00,'BEST')");
    }

    @Test void frozenGradePolicyAndResultsAreScopedToTheirExactCommunity() {
        var policy=grades.policy(981001,983001,984001);
        assertEquals(new BigDecimal("70.00"),policy.passingPercentage());
        assertEquals("BEST",policy.resultStrategy());
        assertNull(grades.policy(981002,983001,984001));
        assertNull(grades.policy(981001,983002,984001));
        assertEquals(981001,grades.stage(981001,985001,984001).candidateId());
        assertNull(grades.stage(981002,985001,984001));
        assertNull(grades.stage(981001,985002,984002));
    }

    @Test void uniqueAttemptGradeAndTenantFKPreventDuplicateOrCrossTenantScore() {
        var attempt=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        assertTrue(attempts.submit(981001,attempt.id(),"{\"answer\":\"a\"}"));
        var score=new RecruitmentObjectiveScorer.Score(8,10,1,2,true);
        grades.insertGrade(981001,attempt.id(),score,new BigDecimal("70.00"));
        assertEquals(1,grades.grades(981001,985001,984001).size());
        assertTrue(grades.grades(981001,985001,984001).get(0).passed());
        assertTrue(grades.grades(981002,985001,984001).isEmpty());
        assertThrows(DataIntegrityViolationException.class,()->
            grades.insertGrade(981001,attempt.id(),score,new BigDecimal("70.00")));
        assertThrows(DataIntegrityViolationException.class,()->
            grades.insertGrade(981002,attempt.id(),score,new BigDecimal("70.00")));
    }

    @Test void publishedProcessCannotBeChangedByPolicyUpdate() {
        assertFalse(grades.configure(981001,982001,983001,984001,0,
            new GradePolicy(new BigDecimal("70.00"),"LAST")));
        assertEquals("BEST",grades.policy(981001,983001,984001).resultStrategy());
    }
    @Test void decisionAttemptMustBelongToExactApplicationAndStage() {
        var attempt=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        assertTrue(attempts.submit(981001,attempt.id(),"{\"answer\":\"a\"}"));
        grades.insertGrade(981001,attempt.id(),
            new RecruitmentObjectiveScorer.Score(8,10,1,2,true),
            new BigDecimal("70.00"));

        assertThrows(DataIntegrityViolationException.class,()->
            decisions.insertObjectiveDecision(981001,985001,984001,981002,
                "APPROVE","Nota de corte alcançada",987654321L));
        long decisionId=decisions.insertObjectiveDecision(
            981001,985001,984001,981002,
            "APPROVE","Nota de corte alcançada",attempt.id());
        assertTrue(decisionId>0);
        assertTrue(grades.finalized(981001,985001,984001));
        assertFalse(grades.finalized(981002,985001,984001));
        assertEquals("OBJECTIVE_GRADE",jdbc.queryForObject(
            "SELECT decision_source FROM recruitment_stage_decisions "
                +"WHERE community_id=981001 AND application_id=985001 AND stage_id=984001",
            String.class));
        assertEquals(attempt.id(),jdbc.queryForObject(
            "SELECT objective_attempt_id FROM recruitment_stage_decisions "
                +"WHERE community_id=981001 AND application_id=985001 AND stage_id=984001",
            Long.class));
        assertThrows(DataIntegrityViolationException.class,()->
            decisions.insertObjectiveDecision(981001,985001,984001,981002,
                "APPROVE","Tentativa duplicada",attempt.id()));
        assertNull(decisions.decision(981002,985001,984001));
    }

}
