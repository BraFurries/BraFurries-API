package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogs;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotStatus;
import com.Brafurries.API.auth.common.TokenService;
import org.springframework.security.core.Authentication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AdminBotLiveStreamServiceTest {
    private HttpServer server;
    private AdminBotLiveStreamService bridge;
    private final TokenService tokens = new TokenService("test-signing-secret", 900, 1209600);
    private final String bearer = tokens.generateAccessToken("admin@example.test", java.util.List.of("ADMIN"), java.util.List.of());
    private final Authentication authenticated = tokens.authenticateAccessToken(bearer);

    @AfterEach
    void cleanup() {
        if (bridge != null) bridge.shutdown();
        if (server != null) server.stop(0);
    }

    @Test
    void mapsLiveStatusAndLogRecordsToExistingPublicDtos() throws Exception {
        var mapper = new AdminBotStatusService(
            RestClient.builder().build(), "http://127.0.0.1:1", "test"
        );
        AdminBotStatus status = (AdminBotStatus) mapper.decodeLiveEvent("status", """
            {"bot_name":"Coddy","ok":true,"state":"online","ready":true,
             "connected":true,"ping_ms":43.5,"memory_used_mb":100.2,
             "uptime_seconds":900,"commands_loaded":40}
            """);
        assertEquals("online", status.status());
        assertEquals(43.5, status.pingMs());
        assertEquals(100.2, status.memoryUsedMb());

        AdminBotLogs logs = (AdminBotLogs) mapper.decodeLiveEvent("logs", """
            {"items":[{"sequence":4,"timestamp":"2026-10-08T12:00:00Z",
             "level":"ERROR","logger":"test","message":"event"}],
             "limit":1000,"latestSequence":5,"nextSequence":4,
             "cursorExpired":false}
            """);
        assertEquals(4L, logs.items().getFirst().sequence());
        assertEquals(4L, logs.nextSequence());
        assertEquals(5L, logs.latestSequence());
    }

    @Test
    void refusesStreamWhenInternalCredentialsAreAbsent() {
        var mapper = new AdminBotStatusService(
            RestClient.builder().build(), "http://127.0.0.1:1", ""
        );
        bridge = new AdminBotLiveStreamService(mapper, tokens, "http://127.0.0.1:1", "");
        var error = assertThrows(ResponseStatusException.class,
            () -> bridge.subscribe("INFO", 10L, authenticated));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void rejectsInvalidAdminTokenBeforeOpeningUpstream() {
        var mapper = mock(AdminBotStatusService.class);
        bridge = new AdminBotLiveStreamService(
            mapper, tokens, "http://127.0.0.1:1", "secret"
        );
        Authentication regular = tokens.authenticateAccessToken(tokens.generateAccessToken(
            "member@example.test", java.util.List.of("USER"), java.util.List.of()
        ));
        var error = assertThrows(ResponseStatusException.class,
            () -> bridge.subscribe("INFO", 10L, regular));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void endsDownstreamNormallyWhenUpstreamCloses() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin-stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            byte[] payload = "event: status\ndata: {\"ready\":true}\n\n"
                .getBytes(StandardCharsets.UTF_8);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
                output.flush();
            }
        });
        server.start();
        var mapper = mock(AdminBotStatusService.class);
        when(mapper.decodeLiveEvent(eq("status"), anyString()))
            .thenReturn(java.util.Map.of("status", "online"));
        bridge = new AdminBotLiveStreamService(
            mapper, tokens, "http://127.0.0.1:" + server.getAddress().getPort(), "secret"
        );
        var mvc = MockMvcBuilders.standaloneSetup(new AdminBotStreamController(bridge)).build();
        var result = mvc.perform(get("/admin/bot/stream")
            .param("level", "INFO").principal(authenticated).accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        var completed = mvc.perform(asyncDispatch(result))
            .andExpect(status().isOk()).andReturn();
        assertTrue(completed.getResponse().getContentAsString().contains("event:status"));
    }

    @Test
    void proxiesAuthenticatedUpstreamStreamWithCursor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/admin-stream", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] frame = ("event: status\ndata: " +
                "{\"ok\":true,\"ready\":true,\"connected\":true}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(frame);
                output.flush();
            }
        });
        server.start();
        var statusMapper = spy(new AdminBotStatusService(
            RestClient.builder().build(), "http://127.0.0.1:1", "secret"
        ));
        bridge = new AdminBotLiveStreamService(
            statusMapper, tokens, "http://127.0.0.1:" + server.getAddress().getPort(), "secret"
        );
        bridge.subscribe("WARNING", 41L, authenticated);
        verify(statusMapper, timeout(3000)).decodeLiveEvent(
            eq("status"), contains("\"ok\":true")
        );
        assertEquals("Bearer secret", token.get());
        assertTrue(query.get().contains("after=41"));
        assertTrue(query.get().contains("level=WARNING"));
    }
}
