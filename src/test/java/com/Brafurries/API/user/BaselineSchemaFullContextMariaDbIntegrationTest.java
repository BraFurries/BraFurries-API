package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full Spring web-context boot against the pinned, disposable Database baseline.
 *
 * Opt-in only: the canonical SQL is copied from the Database checkout by CI,
 * never from a live database. MockMvc does not bind an external HTTP listener.
 * The test never starts Discord, Coddy, a production database or a deployment.
 */
@EnabledIfEnvironmentVariable(named = "BRF_BASELINE_JPA_INTEGRATION", matches = "true")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false",
    "jwt.base-key=brf-disposable-integration-only-not-a-real-secret-32bytes",
    "spring.task.scheduling.enabled=false",
    "app.announcements.discord.enabled=false",
    "app.announcements.telegram.enabled=false",
    "discord_token=",
    "app.bot.base-url=http://127.0.0.1:9"
})
class BaselineSchemaFullContextMariaDbIntegrationTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_disposable_full_context")
        .withUsername("brf_synthetic_test")
        .withPassword("brf_synthetic_test_only")
        .withInitScript("brafurries_schema_baseline.sql");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test
    void startsFullWebContextAndServesPublicMetadataWithCanonicalDatabase() throws Exception {
        Integer tables = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",
            Integer.class
        );
        assertEquals(107, tables);
        mvc.perform(get("/meta/locales")).andExpect(status().isOk());
    }
}
