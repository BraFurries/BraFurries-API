package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogs;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class AdminBotStatusServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(AdminBotStatusService.class)
        .withPropertyValues(
            "app.bot.base-url=http://127.0.0.1:18088",
            "app.bot.status-token=test-only-token",
            "app.bot.connect-timeout-ms=100",
            "app.bot.read-timeout-ms=100"
        );

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsCompleteCoddyTelemetryWithoutUsingApiJvmMetrics() {
        server.createContext("/status", exchange -> respond(exchange, 200, """
            {"ok":true,"state":"online","ready":true,"connected":true,"uptime_seconds":1234,
             "started_at":"2026-09-04T12:00:00Z","last_ready_at":"2026-09-04T12:01:00Z",
             "memory_used_mb":321.5,"memory_limit_mb":1024,"cpu_usage_percent":8.75,"ping_ms":42,
             "guilds":12,"users":3456,"commands_loaded":22,"cogs_loaded":8,
             "python_version":"3.13.1","discord_py_version":"2.5.2","process_id":99}
            """));

        AdminBotStatus status = service().getStatus();

        assertEquals("online", status.status());
        assertEquals(1234L, status.uptimeSeconds());
        assertEquals(321.5, status.memoryUsedMb());
        assertEquals(8.75, status.cpuUsagePercent());
        assertEquals(42.0, status.pingMs());
        assertEquals(12L, status.guilds());
        assertEquals("3.13.1", status.pythonVersion());
    }

    @Test
    void createsProductionServiceBeanThroughSpringDependencyInjection() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            assertEquals(1, context.getBeansOfType(AdminBotStatusService.class).size());
            assertTrue(AdminBotStatusService.class.getConstructors()[0].isAnnotationPresent(Autowired.class));
        });
    }

    @Test
    void mapsDegradedRestartingAndNullableTelemetry() {
        server.createContext("/status", exchange -> respond(exchange, 200, "{\"ok\":true,\"state\":\"restarting\",\"ready\":false,\"connected\":false}"));
        AdminBotStatus restarting = service().getStatus();
        assertEquals("restarting", restarting.status());
        assertNull(restarting.memoryUsedMb());
        assertNull(restarting.cpuUsagePercent());

        server.removeContext("/status");
        server.createContext("/status", exchange -> respond(exchange, 200, "{\"ok\":false,\"state\":\"online\",\"ready\":true,\"connected\":true}"));
        assertEquals("degraded", service().getStatus().status());
    }

    @Test
    void returnsOfflineWithOnlyUnknownFieldsWhenCoddyIsUnavailable() {
        AdminBotStatus status = new AdminBotStatusService(RestClient.builder().build(), "http://127.0.0.1:1", "token").getStatus();

        assertEquals("offline", status.status());
        assertNull(status.uptimeSeconds());
        assertNull(status.pingMs());
        assertNull(status.memoryUsedMb());
        assertNull(status.cpuUsagePercent());
    }

    @Test
    void proxiesLogsWithTokenAndSupportedFilters() throws Exception {
        server.createContext("/logs", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"ok\":true,\"items\":[{\"sequence\":7,\"timestamp\":\"2026-09-04T12:00:00Z\",\"level\":\"WARNING\",\"logger\":\"discord.gateway\",\"message\":\"slow gateway\"},\"plain line\"],\"totalBuffered\":2,\"limit\":25,\"oldestSequence\":7,\"latestSequence\":8,\"cursorExpired\":false}");
        });

        AdminBotLogs logs = service().getLogs(25, "warning");

        assertEquals("available", logs.status());
        assertEquals("Bearer bot-secret", authorization.get());
        assertTrue(query.get().contains("limit=25"));
        assertTrue(query.get().contains("level=warning"));
        assertEquals("WARNING", logs.items().getFirst().level());
        assertEquals(7L, logs.items().getFirst().sequence());
        assertEquals("discord.gateway", logs.items().getFirst().logger());
        assertEquals("plain line", logs.items().get(1).message());
        assertEquals(2, logs.totalBuffered());
        assertEquals(25, logs.limit());
        assertEquals(7L, logs.oldestSequence());
        assertEquals(8L, logs.latestSequence());
        assertFalse(logs.cursorExpired());
        assertNull(logs.nextSequence()); // Legacy upstream must not invent a safe cursor.
        assertFalse(query.get().contains("after="));
        assertFalse(new ObjectMapper().writeValueAsString(logs).contains("bot-secret"));
        assertFalse(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(logs)).has("botStatus"));
    }

    @Test
    void propagatesSafeCursorSeparatelyFromHeadAndDoesNotFetchStatus() throws Exception {
        server.createContext("/logs", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, """
                {"items":[{"sequence":2,"level":"WARNING","message":"match"}],
                 "oldestSequence":1,"latestSequence":600,"nextSequence":2,"cursorExpired":false}
                """);
        });
        server.createContext("/status", exchange -> {
            throw new AssertionError("Logs must not request operational status");
        });
        AdminBotLogs logs = service().getLogs(1, "WARNING", 0L);
        assertEquals(1L, logs.oldestSequence());
        assertEquals(600L, logs.latestSequence());
        assertEquals(2L, logs.nextSequence());
        assertFalse(logs.cursorExpired());
        assertTrue(query.get().contains("after=0"));
        String json = new ObjectMapper().writeValueAsString(logs);
        assertFalse(json.contains("botStatus"));
        assertFalse(json.contains("bot-secret"));
    }

    @Test
    void forwardsHistoryCursorWithoutReinterpretingFiltersOrLimit() {
        server.createContext("/logs", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, """
                {"items":[{"sequence":4999,"level":"INFO","message":"older"}],
                 "totalBuffered":10000,"limit":1000,"oldestSequence":1,
                 "latestSequence":10000,"nextSequence":10000,"cursorExpired":false}
                """);
        });

        AdminBotLogs logs = service().getLogs(1000, "INFO", null, 5000L);

        assertTrue(query.get().contains("limit=1000"));
        assertTrue(query.get().contains("level=INFO"));
        assertTrue(query.get().contains("before=5000"));
        assertFalse(query.get().contains("after="));
        assertEquals(4999L, logs.items().getFirst().sequence());
        assertEquals(10000, logs.totalBuffered());
        assertEquals(10000L, logs.nextSequence());
    }

    @Test
    void propagatesExpiredRestartCursorAndEmptyBuffer() {
        server.createContext("/logs", exchange -> respond(exchange, 200, """
            {"items":[],"totalBuffered":0,"oldestSequence":null,"latestSequence":0,
             "nextSequence":0,"cursorExpired":true}
            """));
        AdminBotLogs logs = service().getLogs(100, null, 5832L);
        assertTrue(logs.cursorExpired());
        assertNull(logs.oldestSequence());
        assertEquals(0L, logs.latestSequence());
        assertEquals(0L, logs.nextSequence());
    }

    @Test
    void returnsUnavailableLogsForCoddyErrorsAndTimeouts() {
        server.createContext("/logs", exchange -> respond(exchange, 503, "{\"ok\":false}"));
        assertOfflineLogs(service().getLogs(25, null), 25);

        server.removeContext("/logs");
        server.createContext("/logs", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"ok\":true,\"logs\":[]}");
        });
        assertOfflineLogs(serviceWithReadTimeout(50).getLogs(null, null), 200);
    }

    @Test
    void usesCurrentCoddyPortByDefaultAndDoesNotCacheOperationalStatus() throws NoSuchMethodException {
        assertEquals("http://127.0.0.1:18088", AdminBotStatusService.normalizeBaseUrl(null));
        assertFalse(AdminBotStatusService.class.getMethod("getStatus").isAnnotationPresent(Cacheable.class));
    }

    private void assertOfflineLogs(AdminBotLogs logs, int expectedLimit) {
        assertEquals("unavailable", logs.status());
        assertTrue(logs.items().isEmpty());
        assertEquals(0, logs.totalBuffered());
        assertEquals(expectedLimit, logs.limit());
        assertNull(logs.nextSequence());
        assertNull(logs.latestSequence());
        assertFalse(logs.cursorExpired());
    }

    private AdminBotStatusService service() {
        return new AdminBotStatusService(RestClient.builder().build(), baseUrl(), "bot-secret");
    }

    private AdminBotStatusService serviceWithReadTimeout(int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new AdminBotStatusService(RestClient.builder().requestFactory(requestFactory).build(), baseUrl(), "bot-secret");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
