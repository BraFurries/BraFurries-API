package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    RecruitmentPublicationRepository.class,
    RecruitmentTrackingRepository.class
})
class RecruitmentTrackingJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_tracking_disposable")
        .withUsername("brf_tracking_synthetic")
        .withPassword("brf_tracking_synthetic_only")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDraftRepository drafts;
    @Autowired RecruitmentCandidateRepository candidates;
    @Autowired RecruitmentPublicationRepository publication;
    @Autowired RecruitmentTrackingRepository tracking;

    @BeforeEach void seed() {
        jdbc.update(
            "INSERT INTO users (id,username) VALUES (981001,'applicant'),(981002,'reviewer')"
        );
        jdbc.update(
            "INSERT INTO communities (id,name,owner_user_id) VALUES "
                + "(981001,'Tenant A',981002),(981002,'Tenant B',981002)"
        );
        jdbc.update(
            "INSERT INTO user_community_status (community_id,user_id,member_since,is_present,banned) "
                + "VALUES (981001,981001,UTC_TIMESTAMP(),1,0)"
        );
    }

    private long submitOne() {
        long process = drafts.create(981001,981002,"Selection",null);
        var stage = drafts.appendDraftStage(
            981001,process,"Application","Application",
            RecruitmentDraftDtos.StageType.APPLICATION,0L
        );
        long version = publication.drafts(981001,process).get(0).id();
        assertTrue(publication.publish(981001,process,version,1L));
        assertTrue(candidates.openEnrollment(
            981001,process,LocalDateTime.now(ZoneOffset.UTC).plusDays(2),2L
        ));
        return candidates.submit(
            981001,process,version,981001,stage.id(),
            candidates.stageIds(981001,process,version),
            "{\"why\":\"Quero contribuir\"}","a".repeat(64)
        ).applicationId();
    }

    @Test void ownReceiptsNeverExposeInternalStageOrApplicationStatus() {
        long applicationId = submitOne();
        jdbc.update(
            "UPDATE recruitment_applications SET status='AWAITING_PUBLICATION' "
                + "WHERE community_id=981001 AND id=?",applicationId
        );
        var mine = tracking.own(981001,981001,null);
        assertEquals(1,mine.size());
        assertEquals(applicationId,mine.get(0).id());
        assertEquals("RECEIVED",mine.get(0).publicStatus());
        assertNotNull(mine.get(0).submittedAt());
        assertEquals("RECEIVED",tracking.ownById(981001,981001,applicationId).publicStatus());

        assertNull(tracking.ownById(981001,981002,applicationId));
        assertNull(tracking.ownById(981002,981001,applicationId));
        assertEquals(0,tracking.own(981002,981001,null).size());
    }

    @Test void reviewerCanSeeAnswersAndStagesOnlyInOwnCommunity() {
        long applicationId = submitOne();
        long processId = tracking.reviewById(981001,applicationId).processId();

        assertEquals(1,tracking.reviewProcesses(981001,981002).size());
        assertEquals(0,tracking.reviewProcesses(981001,981001).size());
        assertEquals(0,tracking.reviewProcesses(981002,981002).size());
        assertEquals(1,tracking.queue(981001,processId,981002,null).size());
        assertEquals(0,tracking.queue(981001,processId,981001,null).size());
        assertEquals(0,tracking.queue(981002,processId,981002,null).size());

        var item = tracking.reviewById(981001,applicationId);
        assertEquals(981001,item.candidateUserId());
        assertEquals("IN_PROGRESS",item.internalStatus());
        assertNull(tracking.reviewById(981002,applicationId));

        var stages = tracking.reviewStages(981001,applicationId);
        assertEquals(1,stages.size());
        assertEquals("AWAITING_REVIEW",stages.get(0).internalStatus());
        assertEquals(0,tracking.reviewStages(981002,applicationId).size());

        var answers = tracking.candidateAnswers(981001,applicationId);
        assertNotNull(answers);
        assertEquals("{\"why\":\"Quero contribuir\"}",answers.json());
        assertEquals(stages.get(0).stageId(),answers.stageId());
        assertNull(tracking.candidateAnswers(981002,applicationId));
    }

    @Test void paginatedQueueUsesStableIdsAndCannotEnumerateCrossProcess() {
        long first = submitOne();
        long processId = tracking.reviewById(981001,first).processId();
        assertEquals(1,tracking.queue(981001,processId,981002,null).size());
        assertEquals(0,tracking.queue(981001,processId,981002,first).size());
        assertEquals(0,tracking.own(981001,981001,first).size());
        assertEquals(0,tracking.queue(981001,99999999L,981002,null).size());
    }
}
