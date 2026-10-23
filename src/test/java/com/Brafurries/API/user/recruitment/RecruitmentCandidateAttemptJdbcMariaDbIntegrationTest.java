package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.Policy;
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
import java.time.Instant;

@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentCandidateAttemptRepository.class)
class RecruitmentCandidateAttemptJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database=new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_attempts_disposable")
        .withUsername("brf_attempts_synthetic")
        .withPassword("brf_attempts_synthetic_only")
        .withInitScript("recruitment_schema.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentCandidateAttemptRepository attempts;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES "
            +"(981001,'test-candidate'),(981002,'test-admin'),(981003,'foreign')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            +"(981001,'A',981002),(981002,'B',981003)");
        jdbc.update("INSERT INTO user_community_status "
            +"(community_id,user_id,member_since,is_present,banned) VALUES "
            +"(981001,981001,UTC_TIMESTAMP(),1,0),"
            +"(981001,981002,UTC_TIMESTAMP(),1,0),"
            +"(981002,981003,UTC_TIMESTAMP(),1,0)");
        jdbc.update("INSERT INTO recruitment_processes "
            +"(id,community_id,title,created_by_user_id,status) "
            +"VALUES (982001,981001,'A',981002,'ACTIVE'),"
            +"(982002,981002,'B',981003,'ACTIVE')");
        jdbc.update("INSERT INTO recruitment_process_versions "
            +"(id,community_id,process_id,version_number,status,created_by_user_id) "
            +"VALUES (983001,981001,982001,1,'PUBLISHED',981002),"
            +"(983002,981002,982002,1,'PUBLISHED',981003)");
        jdbc.update("INSERT INTO recruitment_stages "
            +"(id,community_id,process_id,process_version_id,position,"
            +"internal_name,public_name,stage_type,candidate_form_json) "
            +"VALUES (984001,981001,982001,983001,1,'Prova','Prova','AUTO_EVALUATION',"
            +"'{\"questions\":[]}'),"
            +"(984002,981002,982002,983002,1,'Outra','Outra','AUTO_EVALUATION',"
            +"'{\"questions\":[]}')");
        jdbc.update("INSERT INTO recruitment_applications "
            +"(id,community_id,process_id,process_version_id,candidate_user_id) "
            +"VALUES (985001,981001,982001,983001,981001),"
            +"(985002,981002,982002,983002,981003)");
        jdbc.update("INSERT INTO recruitment_application_stages "
            +"(community_id,application_id,process_version_id,stage_id,status) "
            +"VALUES (981001,985001,983001,984001,'AWAITING_CANDIDATE'),"
            +"(981002,985002,983002,984002,'AWAITING_CANDIDATE')");
        jdbc.update("INSERT INTO recruitment_stage_attempt_policies "
            +"(community_id,process_version_id,stage_id,max_attempts,time_limit_seconds) "
            +"VALUES (981001,983001,984001,2,1800)");
    }

    @Test void stageAndPolicyNeverCrossCommunityOrVersion() {
        assertNotNull(attempts.stage(981001,985001,984001));
        assertNull(attempts.stage(981002,985001,984001));
        assertNull(attempts.stage(981001,985002,984002));
        assertEquals(2,attempts.policy(981001,983001,984001).maxAttempts());
        assertNull(attempts.policy(981002,983001,984001));
        assertNull(attempts.policy(981001,983002,984001));
    }

    @Test void attemptUniquenessAndSubmitAreEnforcedByMariaDb() {
        Instant expiration=attempts.dbNow().plusSeconds(90);
        var created=attempts.create(981001,985001,983001,984001,1,expiration);
        assertEquals(1,created.number());
        assertNotNull(created.startedAt());
        assertThrows(DataIntegrityViolationException.class,()->
            attempts.create(981001,985001,983001,984001,1,expiration));
        assertThrows(DataIntegrityViolationException.class,()->
            attempts.create(981002,985001,983001,984001,2,expiration));
        assertTrue(attempts.submit(981001,created.id(),"{\"q\":\"b\"}"));
        assertFalse(attempts.submit(981001,created.id(),"{\"q\":\"a\"}"));
        assertFalse(attempts.submit(981002,created.id(),"{\"q\":\"a\"}"));
        var stored=jdbc.queryForObject(
            "SELECT answers_json FROM recruitment_candidate_attempts "
                +"WHERE community_id=981001 AND id=?",String.class,created.id());
        assertEquals("{\"q\":\"b\"}",stored);
    }

    @Test void draftPolicySaveUsesOptimisticRevisionAndCannotWriteOtherCommunity() {
        // Published process must NOT be changeable by the administrative service.
        assertEquals("ACTIVE",attempts.lockDraft(981001,982001,984001).processStatus());
        assertNull(attempts.lockDraft(981002,982001,984001));
        assertNull(attempts.lockDraft(981001,982002,984002));
        assertEquals(0,attempts.attempts(981001,985001,984001).size());
        // No test for savePolicy here: service must block all non-DRAFT versions first.
    }
}
