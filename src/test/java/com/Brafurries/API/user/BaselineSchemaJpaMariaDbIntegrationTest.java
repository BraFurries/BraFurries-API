package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityStore;
import com.Brafurries.API.auth.social.SparseAccountConsolidationExecutor;
import com.Brafurries.API.auth.social.SparseAccountOwnershipGuard;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserDiscordLinkChallenge;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserAccountConsolidationRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.Brafurries.API.repository.community.CommunityStoreRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import java.util.List;
import jakarta.persistence.EntityManager;
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

/**
 * Opt-in JPA and control-plane JDBC contracts against the exact schema owned by
 * BraFurries-Database. The baseline is copied into test resources by the
 * disposable GitHub Actions integration job and NEVER checked into API.
 * All SQL writes below create test data only inside Testcontainers.
 */
@EnabledIfEnvironmentVariable(named = "BRF_BASELINE_JPA_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ThemeControlPlaneStore.class, BackupControlPlaneStore.class,
    SparseAccountConsolidationExecutor.class, SparseAccountOwnershipGuard.class})
class BaselineSchemaJpaMariaDbIntegrationTest {
    private static final long GUILD_A = 991001L;
    private static final long GUILD_B = 991002L;

    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_disposable_baseline_compat")
        .withUsername("brf_synthetic_test")
        .withPassword("brf_synthetic_test_only")
        .withInitScript("brafurries_schema_baseline.sql");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommunityRepository communities;
    @Autowired private CommunityStoreRepository communityStore;
    @Autowired private EntityManager entityManager;
    @Autowired private ThemeControlPlaneStore themes;
    @Autowired private BackupControlPlaneStore backups;
    @Autowired private UserRepository users;
    @Autowired private UserDiscordRepository discords;
    @Autowired private UserSocialIdentityRepository socialIdentities;
    @Autowired private UserAccountConsolidationRepository consolidationAudit;
    @Autowired private UserCommunityStatusRepository memberships;
    @Autowired private SparseAccountConsolidationExecutor sparseMerge;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void validatesEntityMappingsAgainstDatabaseOwnedSchema() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",
            Integer.class
        );
        assertEquals(110, count);

        Community community = new Community();
        community.setName("Synthetic tenant");
        community = communities.saveAndFlush(community);
        assertNotNull(community.getId());
        assertEquals("Synthetic tenant", communities.findById(community.getId()).orElseThrow().getName());
    }

    @Test
    void retainsPhysicalCharAndIntegerColumnTypesAfterJpaMappingCorrections() {
        // Verify physical schema remains unchanged: mapping fixes belong in API only.
        String nameType = jdbc.queryForObject(
            "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='community_store' " +
            "AND COLUMN_NAME='item_name'",
            String.class
        );
        String scheduleIdType = jdbc.queryForObject(
            "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_scheduling' " +
            "AND COLUMN_NAME='id'",
            String.class
        );
        String roleColorType = jdbc.queryForObject(
            "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_custom_roles' " +
            "AND COLUMN_NAME='color'",
            String.class
        );
        assertEquals("char(50)", nameType);
        assertEquals("int(11)", scheduleIdType);
        assertEquals("char(32)", roleColorType);
    }

    @Test
    void roundTripsLegacyCharStoreItemWithoutDatabaseAlteration() {
        Community community = new Community();
        community.setName("CHAR compatibility synthetic tenant");
        community = communities.saveAndFlush(community);

        CommunityStore item = new CommunityStore();
        item.setCommunity(community);
        item.setItemName("Community badge");
        item.setItemPrice(50);
        item.setAllowMultiple(false);
        item.setIsService(false);
        Integer itemId = communityStore.saveAndFlush(item).getId();
        entityManager.clear();

        CommunityStore restored = communityStore.findById(itemId).orElseThrow();
        assertEquals("Community badge", restored.getItemName());
        assertEquals(community.getId(), restored.getCommunity().getId());
    }

    @Test
    void themeStoreNeverExposesOrUpdatesAnotherGuildTheme() {
        long own = themes.createTheme(
            GUILD_A, "Autumn", "Synthetic", 7001L, null,
            "UNCHANGED", "UNCHANGED",
            List.of(new ThemeControlPlaneStore.ResourceWrite("ROLE", 101L, "Autumn role"))
        );
        long foreign = themes.createTheme(
            GUILD_B, "Winter", "Synthetic", 7002L, null,
            "UNCHANGED", "UNCHANGED",
            List.of(new ThemeControlPlaneStore.ResourceWrite("ROLE", 202L, "Winter role"))
        );

        assertNotNull(themes.findTheme(GUILD_A, own));
        assertNull(themes.findTheme(GUILD_A, foreign));
        assertNull(themes.findThemeIncludingDeleted(GUILD_A, foreign));
        assertEquals(List.of(own), themes.listThemes(GUILD_A).stream().map(t -> t.id()).toList());

        boolean overwritten = themes.updateTheme(
            GUILD_A, foreign, "Hijacked", null, "UNCHANGED", "UNCHANGED", List.of()
        );
        assertFalse(overwritten);
        assertFalse(themes.softDeleteTheme(GUILD_A, foreign));
        assertEquals("Winter", themes.findTheme(GUILD_B, foreign).name());
        assertEquals(1, themes.listResourceChanges(foreign).size());
        assertEquals("Winter role", themes.listResourceChanges(foreign).getFirst().targetName());
    }

    @Test
    void backupStoreRejectsCrossGuildSnapshotIdsAndReadsSettingsPerGuild() {
        jdbc.update(
            "INSERT INTO backup_discord(id,guild_id,backup_type,original_name) VALUES (?,?,?,?)",
            96001, GUILD_A, "normal", "A"
        );
        jdbc.update(
            "INSERT INTO backup_discord(id,guild_id,backup_type,original_name) VALUES (?,?,?,?)",
            96002, GUILD_B, "normal", "B"
        );

        assertNotNull(backups.findBackup(GUILD_A, 96001));
        assertNull(backups.findBackup(GUILD_A, 96002));
        assertEquals(List.of(96001), backups.listBackups(GUILD_A).stream().map(b -> b.id()).toList());
        assertFalse(backups.deleteBackupIfIdle(GUILD_A, 96002));
        assertNotNull(backups.findBackup(GUILD_B, 96002));

        backups.writeSettings(GUILD_A, true, "weekly", 10080);
        assertTrue(backups.readSettings(GUILD_A).periodicEnabled());
        assertFalse(backups.readSettings(GUILD_B).periodicEnabled());
    }
    private record SparseFixture(User website, User legacy, UserDiscordLinkChallenge proof) {}

    private SparseFixture sparseFixture(String suffix, long discordId) {
        User website = new User();
        website.setEmail("synthetic-google-" + suffix + "@example.invalid");
        website.setDisplayName("Google profile");
        website = users.saveAndFlush(website);

        User legacy = new User();
        legacy.setDisplayName("Discord profile");
        legacy = users.saveAndFlush(legacy);

        UserDiscord discord = new UserDiscord();
        discord.setUser(legacy);
        discord.setDiscordUserId(discordId);
        discord.setUsername("legacy_" + suffix);
        discord.setDisplayName("Discord profile");
        discords.saveAndFlush(discord);

        UserSocialIdentity google = new UserSocialIdentity();
        google.setUser(website);
        google.setProvider("google");
        google.setProviderSubject("synthetic-google-sub-" + suffix);
        google.setVerifiedEmail(website.getEmail());
        socialIdentities.saveAndFlush(google);

        UserDiscordLinkChallenge proof = new UserDiscordLinkChallenge();
        proof.setRequester(website);
        proof.setDiscordUserId(discordId);
        proof.setStatus("VERIFIED");
        return new SparseFixture(website, legacy, proof);
    }

    @Test
    @Transactional(isolation = Isolation.SERIALIZABLE)
    void sparseMergeUsesRealMariaDbFksAndPreservesDiscordHistory() {
        SparseFixture f = sparseFixture("ok", 992224440001L);
        Integer absorbed = f.website().getId();
        Integer canonical = f.legacy().getId();

        // A Discord Community remains attached to the same original user_id.
        Community community = new Community();
        community.setName("Synthetic continuity");
        community = communities.saveAndFlush(community);
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(f.legacy());
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.now());
        membership.setApproved(true);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBanned(false);
        membership.setBirthdayMentionable(false);
        memberships.saveAndFlush(membership);
        Integer membershipId = membership.getId();

        Integer actualCanonical = sparseMerge.consolidate(f.website(), f.proof());

        assertEquals(canonical, actualCanonical);
        entityManager.flush();
        entityManager.clear();
        User web = users.findById(absorbed).orElseThrow();
        User legacy = users.findById(canonical).orElseThrow();
        assertNull(web.getEmail());
        assertEquals(canonical, web.getMergedIntoUser().getId());
        assertEquals("synthetic-google-ok@example.invalid", legacy.getEmail());
        assertEquals(1L, legacy.getAuthVersion());
        assertEquals(1L, web.getAuthVersion());
        assertEquals(canonical, socialIdentities
            .findByProviderAndProviderSubject("google", "synthetic-google-sub-ok")
            .orElseThrow().getUser().getId());
        assertEquals(canonical, memberships.findById(membershipId)
            .orElseThrow().getUser().getId());
        assertEquals(canonical, discords.findByDiscordUserId(992224440001L)
            .orElseThrow().getUser().getId());
        assertTrue(consolidationAudit.existsByAbsorbedUserId(absorbed));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sparseMergeRollbackRestoresBothAccountsAfterPostWriteFailure() {
        TransactionTemplate seed = new TransactionTemplate(transactionManager);
        SparseFixture f = seed.execute(status -> sparseFixture("rollback", 992224440003L));
        assertNotNull(f);

        TransactionTemplate atomic = new TransactionTemplate(transactionManager);
        atomic.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            atomic.execute(status -> {
                sparseMerge.consolidate(f.website(), f.proof());
                // Simulate a failure after all writes/audit; Spring must roll
                // back the complete transaction rather than partial owners.
                throw new IllegalStateException("synthetic rollback after merge");
            }));
        assertEquals("synthetic rollback after merge", failure.getMessage());

        TransactionTemplate check = new TransactionTemplate(transactionManager);
        check.execute(status -> {
            User website = users.findById(f.website().getId()).orElseThrow();
            User legacy = users.findById(f.legacy().getId()).orElseThrow();
            assertEquals("synthetic-google-rollback@example.invalid", website.getEmail());
            assertNull(website.getMergedIntoUser());
            assertNull(legacy.getEmail());
            assertEquals(0L, website.getAuthVersion());
            assertEquals(0L, legacy.getAuthVersion());
            assertEquals(website.getId(), socialIdentities
                .findByProviderAndProviderSubject("google", "synthetic-google-sub-rollback")
                .orElseThrow().getUser().getId());
            assertFalse(consolidationAudit.existsByAbsorbedUserId(website.getId()));
            return null;
        });
    }

    @Test
    @Transactional(isolation = Isolation.SERIALIZABLE)
    void sparseMergeCannotAbsorbOtherCommunityRecordsOnRealMariaDb() {
        SparseFixture f = sparseFixture("block", 992224440002L);
        Community community = new Community();
        community.setName("Synthetic website restriction");
        community = communities.saveAndFlush(community);
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(f.website());
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.now());
        membership.setApproved(true);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBanned(false);
        membership.setBirthdayMentionable(false);
        memberships.saveAndFlush(membership);

        assertThrows(ResponseStatusException.class,
            () -> sparseMerge.consolidate(f.website(), f.proof()));
        assertEquals("synthetic-google-block@example.invalid", f.website().getEmail());
        assertNull(f.website().getMergedIntoUser());
        assertFalse(consolidationAudit.existsByAbsorbedUserId(f.website().getId()));
    }

}
