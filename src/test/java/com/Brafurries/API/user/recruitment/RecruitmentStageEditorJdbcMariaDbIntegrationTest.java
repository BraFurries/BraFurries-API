package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;
import static com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import static com.Brafurries.API.user.recruitment.RecruitmentStageEditorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
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
@Import({RecruitmentDraftRepository.class, RecruitmentStageEditorRepository.class})
class RecruitmentStageEditorJdbcMariaDbIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_recruitment_editor_test")
        .withUsername("brf_synthetic_editor")
        .withPassword("brf_synthetic_editor_only")
        .withInitScript("recruitment_schema.sql");

    @TestConfiguration
    static class JacksonConfig {
        @Bean ObjectMapper formMapper() { return new ObjectMapper(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RecruitmentDraftRepository drafts;
    @Autowired RecruitmentStageEditorRepository stages;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users (id,username) VALUES (981001,'recruitment-editor')");
        jdbc.update(
            "INSERT INTO communities (id,name,owner_user_id) VALUES "
                + "(981001,'Community A',981001),(981002,'Community B',981001)"
        );
    }

    private RecruitmentFormDefinition candidate() {
        return new RecruitmentFormDefinition(List.of(
            new Question("question1", "Resposta correta?", QuestionType.SINGLE_CHOICE, true,
                List.of(new Option("a", "Sim"), new Option("b", "Não")),
                null, null, 10, List.of("a"))
        ));
    }

    @Test
    void savesTwoIndependentFormSchemasAndRejectsForeignCommunityReadsAndWrites() {
        long process = drafts.create(981001, 981001, "Moderadores", null);
        var stage = drafts.appendDraftStage(981001, process, "Prova", "Prova",
            StageType.AUTO_EVALUATION, 0L);
        assertNotNull(stage);
        var staff = new RecruitmentFormDefinition(List.of(
            new Question("observation", "Parecer", QuestionType.LONG_TEXT, false,
                List.of(), null, null, null, List.of())
        ));
        var saved = stages.save(981001, process, stage.id(),
            new SaveStageRequest("Exame interno", "Conhecimento", StageType.AUTO_EVALUATION,
                candidate(), staff, 1L));
        assertNotNull(saved);
        assertEquals(List.of("a"),
            saved.candidateForm().questions().get(0).correctAnswers());
        assertEquals("observation", saved.staffForm().questions().get(0).id());
        assertEquals("Conhecimento", stages.find(981001, process, stage.id()).publicName());
        // Saving the exact same values must not return a false 409 on MariaDB.
        var identical = stages.save(981001, process, stage.id(),
            new SaveStageRequest("Exame interno", "Conhecimento", StageType.AUTO_EVALUATION,
                candidate(), staff, 2L));
        assertNotNull(identical);
        assertEquals(3L, identical.draftRevision());

        assertNull(stages.find(981002, process, stage.id()));
        assertNull(stages.save(981002, process, stage.id(),
            new SaveStageRequest("Ataque", "Ataque", StageType.AUTO_EVALUATION,
                candidate(), staff, 0L)));
        assertEquals("Conhecimento", stages.find(981001, process, stage.id()).publicName());
    }

    @Test
    void reordersThroughTemporaryPositionsAndCompactsAfterDelete() {
        long process = drafts.create(981001, 981001, "Moderadores", null);
        var first = drafts.appendDraftStage(981001, process, "Primeira", "Primeira", StageType.ANALYSIS, 0L);
        var second = drafts.appendDraftStage(981001, process, "Segunda", "Segunda", StageType.INTERVIEW, 1L);
        var third = drafts.appendDraftStage(981001, process, "Terceira", "Terceira", StageType.CUSTOM, 2L);
        assertTrue(stages.reorder(981001, process,
            List.of(third.id(), first.id(), second.id()), 3L));
        assertEquals(List.of(third.id(), first.id(), second.id()),
            drafts.stages(981001, process).stream().map(StageResponse::id).toList());
        assertEquals(List.of(1, 2, 3),
            drafts.stages(981001, process).stream().map(StageResponse::position).toList());

        assertTrue(stages.delete(981001, process, first.id(), 4L));
        assertEquals(List.of(third.id(), second.id()),
            drafts.stages(981001, process).stream().map(StageResponse::id).toList());
        assertEquals(List.of(1, 2),
            drafts.stages(981001, process).stream().map(StageResponse::position).toList());
        assertFalse(stages.delete(981002, process, second.id(), 0L));
    }

    @Test
    void refusesBadPermutationsAndMutationAfterVersionPublished() {
        long process = drafts.create(981001, 981001, "Moderadores", null);
        var first = drafts.appendDraftStage(981001, process, "Primeira", "Primeira", StageType.ANALYSIS, 0L);
        var second = drafts.appendDraftStage(981001, process, "Segunda", "Segunda", StageType.ANALYSIS, 1L);
        assertThrows(IllegalArgumentException.class,
            () -> stages.reorder(981001, process, List.of(first.id(), first.id()), 2L));
        assertThrows(IllegalArgumentException.class,
            () -> stages.reorder(981001, process, List.of(first.id(), 99999L), 2L));
        assertFalse(stages.reorder(981002, process, List.of(first.id(), second.id()), 0L));

        jdbc.update("UPDATE recruitment_process_versions SET status='PUBLISHED' "
            + "WHERE community_id=981001 AND process_id=?", process);
        assertNull(stages.save(981001, process, first.id(),
            new SaveStageRequest("New", "New", StageType.ANALYSIS, null, null, 2L)));
        assertFalse(stages.delete(981001, process, first.id(), 2L));
        assertFalse(stages.reorder(981001, process, List.of(second.id(), first.id()), 2L));
    }
}
