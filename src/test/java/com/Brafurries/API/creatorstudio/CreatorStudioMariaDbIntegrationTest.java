package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Creator Studio SQL against the Database-owned migration chain at an exact
 * reviewed SHA. PR CI generates the init script from the canonical baseline
 * and actual Database migrations. The database is private to this disposable
 * Testcontainers instance. No SMTP/Zoho requests or production connections.
 */
@EnabledIfEnvironmentVariable(named = "BRF_CREATOR_STUDIO_JDBC_INTEGRATION", matches = "true")
@Testcontainers
class CreatorStudioMariaDbIntegrationTest {
    private static final int ARTIST_A = 992001;
    private static final int ARTIST_B = 992002;
    private static final int PLATFORM_OWNER = 992003;
    private static final String TEST_SECRET = "synthetic-cpaas-hmac-only-for-disposable-ci";

    @Container
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_creator_disposable")
        .withUsername("brf_creator_synthetic")
        .withPassword("brf_creator_synthetic_password")
        .withInitScript("creator_studio_integration_schema.sql");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void resetAndSeed() {
        var datasource = new DriverManagerDataSource(
            database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));

        // Only synthetic fixtures in a brand-new ephemeral MariaDB container.
        jdbc.update("DELETE FROM creator_studio_retention_notices");
        jdbc.update("DELETE FROM creator_studio_retention_holds");
        jdbc.update("DELETE FROM creator_studio_activity");
        jdbc.update("DELETE FROM creator_studio_reference_links");
        jdbc.update("DELETE FROM creator_studio_commission_steps");
        jdbc.update("DELETE FROM creator_studio_commissions");
        jdbc.update("DELETE FROM users WHERE id IN (992001,992002,992003)");
        jdbc.update("""
            INSERT INTO users(id,username,email,email_verified_at)
            VALUES
              (992001,'ci-creator-a','artist-a@example.invalid',UTC_TIMESTAMP(6)),
              (992002,'ci-creator-b','artist-b@example.invalid',UTC_TIMESTAMP(6)),
              (992003,'ci-creator-owner','owner@example.invalid',UTC_TIMESTAMP(6))
            """);
    }

    private long completedCommission(int owner, String title) {
        jdbc.update("""
            INSERT INTO creator_studio_commissions
                (owner_user_id,title,client_name,requested_on,status,completed_at)
            VALUES (?,?,'Synthetic client',DATE_SUB(UTC_DATE(),INTERVAL 480 DAY),
                    'COMPLETED',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 MONTH))
            """, owner, title);
        return jdbc.queryForObject(
            "SELECT id FROM creator_studio_commissions WHERE owner_user_id=? AND title=?",
            Long.class, owner, title);
    }

    private long notice(int owner, long commission, String kind,
                        String status, boolean confirmed, String ref) {
        if (confirmed) {
            jdbc.update("""
                INSERT INTO creator_studio_retention_notices
                    (owner_user_id,commission_id,completed_at,notice_kind,status,
                     sent_at,recipient_email,delivery_state,delivery_provider,
                     delivery_event_ref,delivery_evidence_at)
                SELECT c.owner_user_id,c.id,c.completed_at,?,'SENT',
                       UTC_TIMESTAMP(6),u.email,'SERVER_ACCEPTED','CI_SYNTHETIC',?,
                       UTC_TIMESTAMP(6)
                  FROM creator_studio_commissions c
                  JOIN users u ON u.id=c.owner_user_id
                 WHERE c.id=? AND c.owner_user_id=?
                """, kind, ref, commission, owner);
        } else if ("SENT".equals(status)) {
            jdbc.update("""
                INSERT INTO creator_studio_retention_notices
                    (owner_user_id,commission_id,completed_at,notice_kind,status,
                     sent_at,recipient_email)
                SELECT c.owner_user_id,c.id,c.completed_at,?,'SENT',
                       UTC_TIMESTAMP(6),u.email
                  FROM creator_studio_commissions c
                  JOIN users u ON u.id=c.owner_user_id
                 WHERE c.id=? AND c.owner_user_id=?
                """, kind, commission, owner);
        } else {
            jdbc.update("""
                INSERT INTO creator_studio_retention_notices
                    (owner_user_id,commission_id,completed_at,notice_kind,status)
                SELECT owner_user_id,id,completed_at,?,?
                  FROM creator_studio_commissions
                 WHERE id=? AND owner_user_id=?
                """, kind, status, commission, owner);
        }
        return jdbc.queryForObject("""
            SELECT id FROM creator_studio_retention_notices
             WHERE owner_user_id=? AND commission_id=? AND notice_kind=?
            """, Long.class, owner, commission, kind);
    }

    private Authentication platformAuthentication(PlatformAuthorizationService identity) {
        Authentication authentication = mock(Authentication.class);
        User owner = new User();
        owner.setId(PLATFORM_OWNER);
        when(identity.requireOwner(authentication)).thenReturn(owner);
        return authentication;
    }

    @Test
    void realCompositeForeignKeysIsolateArtistsAndReportsUseCurrentEpochOnly() {
        long first = completedCommission(ARTIST_A, "Alpha");
        long second = completedCommission(ARTIST_B, "Beta");

        assertThrows(DataIntegrityViolationException.class, () ->
            jdbc.update("""
                INSERT INTO creator_studio_reference_links
                    (owner_user_id,commission_id,label,url,position)
                VALUES (?,?,?,?,1)
                """, ARTIST_A, second, "Not mine", "https://example.invalid/", 1));

        notice(ARTIST_A, first, "D30", "PENDING", false, null);
        notice(ARTIST_B, second, "D30", "SENT", false, null);
        PlatformAuthorizationService identity = mock(PlatformAuthorizationService.class);
        Authentication authentication = platformAuthentication(identity);

        var health = new CreatorStudioRetentionHealthService(identity, jdbc).inspect(authentication);
        assertEquals(2, health.totalNotices());
        assertEquals(1, health.pending());
        assertEquals(1, health.smtpUnconfirmed());
        assertEquals(0, health.providerConfirmed());

        var report = new CreatorStudioRetentionDryRunService(identity, jdbc).inspect(authentication);
        assertEquals(2, report.expiredCommissions());
        assertEquals(2, report.blockedByMissingD30());

        // An old completion epoch must not enter a current-epoch health tally.
        jdbc.update("""
            UPDATE creator_studio_commissions
               SET completed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 14 MONTH)
             WHERE id=? AND owner_user_id=?
            """, first, ARTIST_A);
        assertEquals(1, new CreatorStudioRetentionHealthService(identity, jdbc)
            .inspect(authentication).totalNotices());

        jdbc.update("""
            UPDATE creator_studio_commissions
               SET status='PAUSED',completed_at=NULL
             WHERE id=? AND owner_user_id=?
            """, second, ARTIST_B);
        assertEquals(0, new CreatorStudioRetentionHealthService(identity, jdbc)
            .inspect(authentication).totalNotices());
    }

    @Test
    void purgeRequiresTwoIndependentDeliveryConfirmationsAndPreservesOtherOwner() {
        long unsafe = completedCommission(ARTIST_A, "No provider evidence");
        long eligible = completedCommission(ARTIST_B, "Both confirmed");
        notice(ARTIST_A, unsafe, "D30", "SENT", false, null);
        notice(ARTIST_A, unsafe, "D7", "SENT", false, null);
        notice(ARTIST_B, eligible, "D30", "SENT", true, "synthetic-b-30");
        notice(ARTIST_B, eligible, "D7", "SENT", true, "synthetic-b-7");

        CreatorStudioRetentionPurgeWorker worker = new CreatorStudioRetentionPurgeWorker(jdbc);
        assertFalse(Boolean.TRUE.equals(
            transactions.execute(t -> worker.purgeEligibleCommission(unsafe))));
        assertTrue(Boolean.TRUE.equals(
            transactions.execute(t -> worker.purgeEligibleCommission(eligible))));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM creator_studio_commissions WHERE id=? AND owner_user_id=?",
            Integer.class, unsafe, ARTIST_A));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM creator_studio_commissions WHERE id=? AND owner_user_id=?",
            Integer.class, eligible, ARTIST_B));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM creator_studio_retention_notices WHERE owner_user_id=?",
            Integer.class, ARTIST_A));
    }

    @Test
    void legalHoldAndBouncesBlockPurgeDespiteTwoAcceptedReminders() {
        long held = completedCommission(ARTIST_A, "Legal hold");
        notice(ARTIST_A, held, "D30", "SENT", true, "hold-30");
        notice(ARTIST_A, held, "D7", "SENT", true, "hold-7");
        jdbc.update("""
            INSERT INTO creator_studio_retention_holds
                (owner_user_id,commission_id,active,category,reason,
                 created_by_user_id,updated_by_user_id)
            VALUES (?, ?,1,'DISPUTE','Synthetic legal hold',?,?)
            """, ARTIST_A, held, PLATFORM_OWNER, PLATFORM_OWNER);
        var worker = new CreatorStudioRetentionPurgeWorker(jdbc);
        assertFalse(Boolean.TRUE.equals(
            transactions.execute(t -> worker.purgeEligibleCommission(held))));
        jdbc.update("""
            UPDATE creator_studio_retention_holds
               SET active=0 WHERE owner_user_id=? AND commission_id=?
            """, ARTIST_A, held);
        jdbc.update("""
            UPDATE creator_studio_retention_notices
               SET delivery_state='BOUNCED',delivery_evidence_at=NULL
             WHERE owner_user_id=? AND commission_id=? AND notice_kind='D7'
            """, ARTIST_A, held);
        assertFalse(Boolean.TRUE.equals(
            transactions.execute(t -> worker.purgeEligibleCommission(held))));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM creator_studio_commissions WHERE id=?", Integer.class, held));
    }

    @Test
    void signedSyntheticCpaasEventsRecordAndDoNotReverseBounce() throws Exception {
        long commission = completedCommission(ARTIST_A, "Synthetic webhook");
        long id = notice(ARTIST_A, commission, "D30", "SENT", false, null);
        var webhook = new CreatorStudioZohoCpaasWebhookController(
            jdbc, new ObjectMapper(), TEST_SECRET, "ci-agent");

        String delivered = event(id, "delivered", "event-delivered-ci");
        assertEquals(HttpStatus.OK, transactions.execute(t ->
            webhook.event(signature(delivered), delivered.getBytes(StandardCharsets.UTF_8))
        ).getStatusCode());
        assertEquals("SERVER_ACCEPTED", jdbc.queryForObject(
            "SELECT delivery_state FROM creator_studio_retention_notices WHERE id=?",
            String.class, id));

        String bounce = event(id, "hardbounce", "event-bounce-ci");
        transactions.execute(t ->
            webhook.event(signature(bounce), bounce.getBytes(StandardCharsets.UTF_8)));
        assertEquals("BOUNCED", jdbc.queryForObject(
            "SELECT delivery_state FROM creator_studio_retention_notices WHERE id=?",
            String.class, id));
        String late = event(id, "delivered", "event-late-ci");
        transactions.execute(t ->
            webhook.event(signature(late), late.getBytes(StandardCharsets.UTF_8)));
        assertEquals("BOUNCED", jdbc.queryForObject(
            "SELECT delivery_state FROM creator_studio_retention_notices WHERE id=?",
            String.class, id));

        String tampered = event(id, "delivered", "event-tamper-ci");
        var bad = assertThrows(ResponseStatusException.class, () ->
            transactions.execute(t ->
                webhook.event(signature(tampered), (tampered + " ").getBytes(StandardCharsets.UTF_8))));
        assertEquals(HttpStatus.FORBIDDEN, bad.getStatusCode());
    }

    private String event(long noticeId, String kind, String eventId) {
        return """
            {"mailagent_key":"ci-agent","webhook_request_id":"%s",
             "event_name":["%s"],"event_message":[{"email_info":{
               "is_smtp_trigger":true,"client_reference":"cs-ret-%d",
               "to":[{"email_address":{"address":"artist-a@example.invalid"}}]}}]}
            """.formatted(eventId, kind, noticeId);
    }

    private String signature(String body) {
        try {
            long timestamp = Instant.now().toEpochMilli();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String encoded = Base64.getEncoder().encodeToString(
                mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return "s-algorithm=HmacSHA256;ts=" + timestamp + ";s=" +
                URLEncoder.encode(encoded, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Synthetic CI HMAC unavailable", e);
        }
    }
}
