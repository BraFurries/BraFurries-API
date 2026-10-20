package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityStore;
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
@Import({ThemeControlPlaneStore.class, BackupControlPlaneStore.class})
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

    @Test
    void validatesEntityMappingsAgainstDatabaseOwnedSchema() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",
            Integer.class
        );
        assertEquals(107, count);

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
}
