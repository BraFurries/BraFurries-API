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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Version-locked policy edits against the real existing JSON column. */
@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentDecisionPolicyRepository.class)
class RecruitmentDecisionPolicyJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_policy_disposable")
        .withUsername("brf_policy_synthetic")
        .withPassword("brf_policy_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDecisionPolicyRepository policy;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES (981001,'candidate'),(981002,'manager')");
        jdbc.update("INSERT INTO communities (id,name,owner_user_id) VALUES "
            + "(981001,'Community A',981002),(981002,'Community B',981002)");
        jdbc.update("INSERT INTO recruitment_processes "
            + "(id,community_id,title,created_by_user_id) "
            + "VALUES (982001,981001,'A',981002)");
        jdbc.update("INSERT INTO recruitment_process_versions "
            + "(id,community_id,process_id,version_number,created_by_user_id) "
            + "VALUES (983001,981001,982001,1,981002)");
        jdbc.update("INSERT INTO recruitment_stages "
            + "(id,community_id,process_id,process_version_id,position,"
            + "internal_name,public_name,stage_type) "
            + "VALUES (984001,981001,982001,983001,1,'Review','Review','ANALYSIS')");
    }

    @Test void updateIsTenantScopedRevisionLockedAndFrozenAfterPublication() {
        String stored="{\"reviewMode\":\"COLLECTIVE\",\"decisionMode\":\"MANUAL\",\"minimumAssessments\":3}";
        assertNull(policy.find(981002,982001L,984001L));
        var updated=policy.update(981001,982001L,984001L,0L,stored);
        assertEquals(1,updated.draftRevision());
        assertEquals(stored,updated.policyJson());
        assertNull(policy.find(981001,982002L,984001L));
        assertNull(policy.update(981002,982001L,984001L,0L,stored));
        assertThrows(OptimisticLockingFailureException.class,()->
            policy.update(981001,982001L,984001L,0L,stored));
        jdbc.update("UPDATE recruitment_processes SET status='ACTIVE' "
            + "WHERE community_id=981001 AND id=982001");
        jdbc.update("UPDATE recruitment_process_versions SET status='PUBLISHED' "
            + "WHERE community_id=981001 AND id=983001");
        assertThrows(OptimisticLockingFailureException.class,()->
            policy.update(981001,982001L,984001L,1L,stored));
        assertEquals(stored,policy.find(981001,982001L,984001L).policyJson());
    }
}
