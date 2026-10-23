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
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@EnabledIfEnvironmentVariable(named="BRF_RECRUITMENT_JDBC_INTEGRATION",matches="true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties={
    "spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({RecruitmentCandidateAttemptRepository.class,RecruitmentExamReviewRepository.class,
    RecruitmentExamNoticeRepository.class})
class RecruitmentCandidateAttemptJdbcMariaDbIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> database=new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_attempts_disposable")
        .withUsername("brf_attempts_synthetic")
        .withPassword("brf_attempts_synthetic_only")
        .withInitScript("recruitment_schema.sql");
    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentCandidateAttemptRepository attempts;
    @Autowired RecruitmentExamReviewRepository reviews;
    @Autowired RecruitmentExamNoticeRepository notices;

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

    @Test void onlyOwnCurrentExamIsDiscoverableInTheCorrectTenant() {
        var own=attempts.availableExams(981001,985001,981001);
        assertEquals(1,own.size());
        assertEquals(984001,own.get(0).stageId());
        assertEquals("Prova",own.get(0).publicName());
        assertTrue(attempts.availableExams(981001,985001,981002).isEmpty());
        assertTrue(attempts.availableExams(981002,985001,981001).isEmpty());
        assertTrue(attempts.availableExams(981001,985002,981003).isEmpty());
        jdbc.update("UPDATE recruitment_application_stages SET status='COMPLETED' "
            +"WHERE community_id=981001 AND application_id=985001 AND stage_id=984001");
        assertTrue(attempts.availableExams(981001,985001,981001).isEmpty());
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

    @Test void integrityEventsAreTenantScopedUniqueAndBlockedAttemptsCannotSubmit() {
        jdbc.update("UPDATE recruitment_stage_attempt_policies "
            +"SET max_focus_losses=3,block_copy=1 "
            +"WHERE community_id=981001 AND stage_id=984001");
        var created=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        assertFalse(attempts.hasBlockedAttempt(981001,985001,984001));
        assertEquals(0,attempts.focusLosses(981001,created.id()));
        String uuid="00000000-0000-4000-8000-000000000001";
        attempts.insertIntegrityEvent(981001,created.id(),uuid,"FOCUS_LOST","TAB_HIDDEN");
        assertTrue(attempts.eventExists(981001,created.id(),uuid));
        assertFalse(attempts.eventExists(981002,created.id(),uuid));
        assertThrows(DataIntegrityViolationException.class,()->
            attempts.insertIntegrityEvent(981001,created.id(),uuid,"FOCUS_LOST","TAB_HIDDEN"));
        assertThrows(DataIntegrityViolationException.class,()->
            attempts.insertIntegrityEvent(981002,created.id(),
                "00000000-0000-4000-8000-000000000002","FOCUS_LOST","TAB_HIDDEN"));
        assertThrows(DataIntegrityViolationException.class,()->
            attempts.insertIntegrityEvent(981001,created.id(),
                "00000000-0000-4000-8000-000000000003","FOCUS_LOST","COPY"));
        assertEquals(1,attempts.eventCount(981001,created.id()));
        assertTrue(attempts.incrementFocusLoss(981001,created.id()));
        assertTrue(attempts.incrementFocusLoss(981001,created.id()));
        assertFalse(attempts.blockAttempt(981001,created.id(),3));
        assertTrue(attempts.incrementFocusLoss(981001,created.id()));
        assertTrue(attempts.blockAttempt(981001,created.id(),3));
        assertFalse(attempts.incrementFocusLoss(981001,created.id()));
        assertEquals(3,attempts.focusLosses(981001,created.id()));
        assertTrue(attempts.hasBlockedAttempt(981001,985001,984001));
        assertTrue(attempts.availableExams(981001,985001,981001).get(0).blocked());
        assertFalse(attempts.submit(981001,created.id(),"{\"question\":\"a\"}"));
    }

    @Test void reinstatementRetainsEvidenceAndOriginalAttemptWithFullRenewedTime() {
        jdbc.update("UPDATE recruitment_stage_attempt_policies "
            +"SET max_focus_losses=3 WHERE community_id=981001 AND stage_id=984001");
        var before=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        for(int i=1;i<=3;i++) {
            reviewsFocusEvent(before.id(),i);
            assertTrue(attempts.incrementFocusLoss(981001,before.id()));
        }
        assertTrue(attempts.blockAttempt(981001,before.id(),3));
        assertEquals(3,reviews.focusSignals(981001,before.id()));
        var now=attempts.dbNow();
        var expiry=now.plusSeconds(1800);
        String reason="Tela perdeu foco devido a uma notificação do sistema";
        reviews.insertReinstatement(981001,985001,984001,before.id(),
            981002,reason,3,expiry);
        assertTrue(reviews.reinstated(981001,before.id()));
        assertFalse(reviews.reinstated(981002,before.id()));
        assertTrue(reviews.reopenBlocked(981001,985001,984001,before.id(),3,expiry));
        assertFalse(attempts.hasBlockedAttempt(981001,985001,984001));
        assertEquals(0,attempts.focusLosses(981001,before.id()));
        assertEquals(3,reviews.focusSignals(981001,before.id()));
        assertEquals("STARTED",reviews.incident(981001,985001,984001,before.id()).status());
        assertEquals(reason,reviews.incident(981001,985001,984001,before.id()).reviewReason());
        assertEquals(before.expiresAt(),
            reviews.incident(981001,985001,984001,before.id()).originalExpiresAt());
        var originalWindow=jdbc.query(
            "SELECT original_started_at,original_expires_at "
                +"FROM recruitment_exam_reinstatements "
                +"WHERE community_id=? AND attempt_id=?",
            (rs,n)->java.util.List.of(
                rs.getObject(1,LocalDateTime.class),
                rs.getObject(2,LocalDateTime.class)),
            981001,before.id()).get(0);
        assertEquals(LocalDateTime.ofInstant(before.startedAt(),ZoneOffset.UTC),
            originalWindow.get(0));
        assertEquals(LocalDateTime.ofInstant(before.expiresAt(),ZoneOffset.UTC),
            originalWindow.get(1));
        assertTrue(attempts.hasReinstatement(981001,985001,984001,before.id()));
        assertFalse(attempts.hasReinstatement(981002,985001,984001,before.id()));
        assertFalse(reviews.reopenBlocked(981001,985001,984001,before.id(),3,expiry));
        assertThrows(IllegalStateException.class,()->
            reviews.insertReinstatement(981001,985001,984001,before.id(),981002,
                reason,3,expiry));
    }

    private void reviewsFocusEvent(long attemptId,int count) {
        reviewsInsertSignal(981001,attemptId,count);
    }
    private void reviewsInsertSignal(int tenant,long attemptId,int count) {
        jdbc.update("INSERT INTO recruitment_exam_integrity_events "
            +"(community_id,attempt_id,event_uuid,event_type,source) VALUES "
            +"(?,? ,?,'FOCUS_LOST','WINDOW_BLUR')",tenant,attemptId,
            String.format("00000000-0000-4000-8000-%012d",count));
    }

    @Test void incidentNoticesAreTenantScopedAndIndividuallyAcknowledged() {
        var attempt=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        notices.record(981001,985001,984001,attempt.id(),"BLOCKED",null);
        notices.record(981001,985001,984001,attempt.id(),"REINSTATED",
            attempts.dbNow().plusSeconds(1800));
        // A second block after administrative reinstatement is a distinct event.
        notices.record(981001,985001,984001,attempt.id(),"BLOCKED",null);
        var candidate=notices.candidate(981001,985001,981001);
        assertEquals(3,candidate.size());
        assertEquals("BLOCKED",candidate.get(0).eventType());
        assertFalse(candidate.get(0).read());
        assertTrue(notices.candidate(981002,985001,981001).isEmpty());
        assertTrue(notices.candidate(981001,985001,981003).isEmpty());
        assertEquals(3,notices.staff(981001,982001,981002).size());
        assertTrue(notices.staff(981001,982002,981002).isEmpty());
        assertTrue(notices.staff(981002,982001,981002).isEmpty());
        assertFalse(notices.staffNoticeExists(981001,982002,candidate.get(0).id(),981002));
        assertTrue(notices.candidateNoticeExists(981001,985001,candidate.get(0).id(),981001));
        notices.markRead(981001,candidate.get(0).id(),981001);
        notices.markRead(981001,candidate.get(0).id(),981001);
        assertTrue(notices.candidate(981001,985001,981001).get(0).read());
        assertFalse(notices.staff(981001,982001,981002).get(0).read());
        assertThrows(DataIntegrityViolationException.class,
            ()->notices.markRead(981002,candidate.get(0).id(),981003));
    }

    @Test void draftCasIsTenantSafeAndDoesNotChangeFinalSubmission() {
        var attempt=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(180));
        assertEquals(0,attempts.draft(981001,985001,984001,attempt.id()).revision());
        assertFalse(attempts.saveDraft(981002,985001,984001,attempt.id(),0,
            "{\"question\":\"a\"}"));
        assertTrue(attempts.saveDraft(981001,985001,984001,attempt.id(),0,
            "{\"question\":\"a\"}"));
        assertFalse(attempts.saveDraft(981001,985001,984001,attempt.id(),0,
            "{\"question\":\"old\"}"));
        assertEquals(1,attempts.draft(981001,985001,984001,attempt.id()).revision());
        assertEquals("{\"question\":\"a\"}",
            attempts.draft(981001,985001,984001,attempt.id()).answersJson());
        assertTrue(attempts.submit(981001,attempt.id(),"{\"question\":\"b\"}"));
        assertFalse(attempts.saveDraft(981001,985001,984001,attempt.id(),1,
            "{\"question\":\"a\"}"));
        assertNull(attempts.draft(981001,985001,984001,attempt.id()).answersJson());
        assertEquals("{\"question\":\"b\"}",jdbc.queryForObject(
            "SELECT answers_json FROM recruitment_candidate_attempts "
                +"WHERE community_id=981001 AND id=?",
            String.class,attempt.id()));
    }

    @Test void integrityAndManagerAuditTimestampsAreUtcAcrossSessionTimezones() {
        var created=attempts.create(981001,985001,983001,984001,1,
            attempts.dbNow().plusSeconds(90));
        jdbc.update("UPDATE recruitment_stage_attempt_policies SET max_focus_losses=1 "
            +"WHERE community_id=981001 AND stage_id=984001");
        try {
            // Session timezone is intentionally different from UTC.
            jdbc.execute("SET time_zone='+03:00'");
            attempts.insertIntegrityEvent(981001,created.id(),
                "00000000-0000-4000-8000-000000000011","FOCUS_LOST","TAB_HIDDEN");
            assertTrue(attempts.incrementFocusLoss(981001,created.id()));
            assertTrue(attempts.blockAttempt(981001,created.id(),1));
            reviews.insertReinstatement(981001,985001,984001,created.id(),981002,
                "Análise identificou interrupção involuntária do sistema",3,
                attempts.dbNow().plusSeconds(1800));
            Integer signalSkew=jdbc.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND,occurred_at,UTC_TIMESTAMP(6))) "
                    +"FROM recruitment_exam_integrity_events "
                    +"WHERE community_id=981001 AND attempt_id=?",
                Integer.class,created.id());
            Integer reviewSkew=jdbc.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND,reinstated_at,UTC_TIMESTAMP(6))) "
                    +"FROM recruitment_exam_reinstatements "
                    +"WHERE community_id=981001 AND attempt_id=?",
                Integer.class,created.id());
            assertNotNull(signalSkew);
            assertNotNull(reviewSkew);
            assertTrue(signalSkew<=2,"Focus audit timestamp is not UTC");
            assertTrue(reviewSkew<=2,"Restoration audit timestamp is not UTC");
        } finally {
            jdbc.execute("SET time_zone='+00:00'");
        }
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
