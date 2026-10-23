package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

/**
 * Opt-in JDBC integration against 107-table canonical baseline plus
 * Database PR #12's exact, SHA-pinned additive migration.
 *
 * The combined fixture is built by PR Validation, never deployed to production.
 */
@EnabledIfEnvironmentVariable(named = "BRF_RECRUITMENT_JDBC_INTEGRATION", matches = "true")
@Testcontainers
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RecruitmentDraftRepository.class)
class RecruitmentDraftJdbcMariaDbIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_recruitment_disposable")
        .withUsername("brf_recruitment_synthetic")
        .withPassword("brf_recruitment_only_synthetic")
        .withInitScript("recruitment_schema.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDraftRepository drafts;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES (981001,'recruitment-actor')");
        jdbc.update(
            "INSERT INTO communities (id,name,owner_user_id) VALUES "
                + "(981001,'Community A',981001),(981002,'Community B',981001)"
        );
        jdbc.update(
            "INSERT INTO user_community_status (community_id,user_id,member_since) "
                + "VALUES (981001,981001,UTC_TIMESTAMP())"
        );
    }

    @Test
    void createsScopedProcessAndDraftVersionAndKeepsChangesInDraft() {
        Long id = drafts.create(981001, 981001, "Process A", "Synthetic test");
        ProcessResponse original = drafts.find(981001, id);
        assertNotNull(original);
        assertEquals("DRAFT", original.status());
        assertEquals("PRIVATE", original.visibility());
        assertEquals("CLOSED", original.enrollmentStatus());
        assertEquals(1, original.draftVersion());
        assertEquals(1, drafts.list(981001).size());
        assertEquals(List.of(), drafts.list(981002));

        assertTrue(drafts.updateDraft(981001, id, "Renamed", "Test", 0L));
        assertEquals("Renamed", drafts.find(981001, id).title());
        assertFalse(drafts.updateDraft(981002, id, "Cross-tenant", null, 0L));
        assertNull(drafts.find(981002, id));
        assertEquals("Renamed", drafts.find(981001, id).title());

        jdbc.update(
            "UPDATE recruitment_processes SET status='ACTIVE' "
                + "WHERE id=? AND community_id=981001", id
        );
        assertFalse(drafts.updateDraft(981001, id, "Published override", null, 1L));
    }

    @Test
    void appendsSequentialStagesAndBlocksWrongTenantAndPublishedMutation() {
        Long processId = drafts.create(981001, 981001, "Process A", null);
        StageResponse first = drafts.appendDraftStage(
            981001, processId, "Internal analysis", "In review", StageType.ANALYSIS, 0L
        );
        StageResponse second = drafts.appendDraftStage(
            981001, processId, "Internal interview", "Interview", StageType.INTERVIEW, 1L
        );
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(1, first.position());
        assertEquals(2, second.position());
        assertEquals(first.processVersionId(), second.processVersionId());

        assertEquals(List.of(first, second), drafts.stages(981001, processId));
        assertEquals(List.of(), drafts.stages(981002, processId));
        assertNull(drafts.appendDraftStage(
            981002, processId, "Intruder", "Intruder", StageType.CUSTOM, 0L
        ));

        jdbc.update(
            "UPDATE recruitment_processes SET status='ACTIVE' "
                + "WHERE community_id=981001 AND id=?", processId
        );
        assertNull(drafts.appendDraftStage(
            981001, processId, "Too late", "Too late", StageType.CUSTOM, 2L
        ));
        assertEquals(2, drafts.stages(981001, processId).size());
    }

    @Test
    void staleEditorCannotOverwriteProcessOrAppendStage() {
        long process = drafts.create(981001, 981001, "Seleção", null);
        assertEquals(0L, drafts.find(981001, process).draftRevision());

        assertTrue(drafts.updateDraft(981001, process, "Versão salva", null, 0L));
        assertEquals(1L, drafts.find(981001, process).draftRevision());
        assertFalse(drafts.updateDraft(981001, process, "Sobrescrita perdida", null, 0L));
        assertEquals("Versão salva", drafts.find(981001, process).title());

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
            () -> drafts.appendDraftStage(981001, process, "Etapa obsoleta",
                "Etapa obsoleta", StageType.ANALYSIS, 0L));
        assertTrue(drafts.stages(981001, process).isEmpty());
        assertEquals(1L, drafts.find(981001, process).draftRevision());

        var stage = drafts.appendDraftStage(981001, process, "Nova", "Nova",
            StageType.ANALYSIS, 1L);
        assertNotNull(stage);
        assertEquals(2L, drafts.find(981001, process).draftRevision());
    }

    @Test
    void twoTenantsMaintainIndependentDraftsAndStagePositions() {
        Long first = drafts.create(981001, 981001, "Process A", null);
        Long second = drafts.create(981002, 981001, "Process B", null);
        assertNotEquals(first, second);
        assertEquals(1, drafts.appendDraftStage(
            981001, first, "A", "A", StageType.ANALYSIS, 0L
        ).position());
        assertEquals(1, drafts.appendDraftStage(
            981002, second, "B", "B", StageType.ANALYSIS, 0L
        ).position());
        assertEquals("Process B", drafts.find(981002, second).title());
        assertNull(drafts.find(981002, first));
        assertNull(drafts.find(981001, second));
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",
            Integer.class
        );
        // Canonical pinned schema includes the two incident-notice tables (Database #25).
        assertEquals(124, count);
    }
}
