package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real loopback HTTP between the Spring controller/service and a pinned copy
 * of Coddy's actual Python lifecycle client. Both use synthetic, CI-only
 * inputs. The database is disposed with Testcontainers, never a shared DB.
 */
@EnabledIfEnvironmentVariable(named = "BRF_CODDY_LIVE_HTTP_INTEGRATION", matches = "true")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "server.address=127.0.0.1",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false",
    "jwt.base-key=brf-loopback-test-key-not-a-real-secret-32bytes",
    "app.bot.status-token=brf-loopback-test-only-token",
    "spring.task.scheduling.enabled=false",
    "app.announcements.discord.enabled=false",
    "app.announcements.telegram.enabled=false",
    "discord_token=",
    "app.bot.base-url=http://127.0.0.1:9"
})
class BaselineCoddyLiveHttpIntegrationTest {
    private static final String SYNTHETIC_TOKEN = "brf-loopback-test-only-token";

    @Container
    @ServiceConnection
    static MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6.28")
        .withDatabaseName("brf_disposable_live_http")
        .withUsername("brf_synthetic_test")
        .withPassword("brf_synthetic_test_only")
        .withInitScript("brafurries_schema_baseline.sql");

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void reconcilesTwoGuildsThroughActualCoddyPythonClientAndSpringHttp() throws Exception {
        assertTrue(port > 0);
        assertEquals(107, jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",
            Integer.class
        ));

        Path checkout = Path.of(".ci", "coddy-runtime").toAbsolutePath().normalize();
        Path script = Path.of("src/test/resources/coddy_live_http_contract.py")
            .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(checkout.resolve("core")), "Pinned Coddy checkout is missing");
        assertTrue(Files.isRegularFile(script));

        ProcessBuilder builder = new ProcessBuilder("python3", script.toString());
        builder.directory(checkout.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONPATH", checkout.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("BRF_CODDY_LIVE_HTTP_INTEGRATION", "true");
        builder.environment().put("BRF_LOOPBACK_TEST_PORT", String.valueOf(port));
        builder.environment().put("BRF_LOOPBACK_TEST_TOKEN", SYNTHETIC_TOKEN);
        Process process = builder.start();

        boolean completed = process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Python contract process did not terminate");
            fail("Coddy live HTTP contract timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "Coddy live HTTP contract failed: " + output);
        assertTrue(output.contains("CODDY_LOOPBACK_HTTP_CONTRACT_PASSED"), output);

        // Confirm persisted server state, not merely a successful response mock.
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM community_discord WHERE guild_id IN (991001, 991002)",
            Integer.class
        ));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(DISTINCT community_id) FROM community_discord " +
                "WHERE guild_id IN (991001, 991002) AND membership_sync_state='HEALTHY'",
            Integer.class
        ));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM community_network_member_status " +
                "WHERE network_type='DISCORD' AND external_network_id IN (991001,991002) " +
                "AND is_present=1",
            Integer.class
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(DISTINCT user_id) FROM community_network_member_status " +
                "WHERE network_type='DISCORD' AND external_network_id IN (991001,991002) " +
                "AND is_present=1",
            Integer.class
        ));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM community_network_sync_runs " +
                "WHERE external_network_id IN (991001,991002) AND status='SUCCESS' " +
                "AND \u0060trigger\u0060 IN ('STARTUP','MANUAL')",
            Integer.class
        ));
    }
}
