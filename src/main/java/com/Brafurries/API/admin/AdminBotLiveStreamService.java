package com.Brafurries.API.admin;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogs;
import com.Brafurries.API.auth.common.TokenService;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

/** ADMIN-only SSE bridge. No client is allowed access to the Coddy service token. */
@Service
public class AdminBotLiveStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBotLiveStreamService.class);
    // Streaming remains open while the browser is subscribed. Disconnects
    // and unexpected origin failures are handled by the existing client retry.
    private static final long EMITTER_LIFETIME_MS = 0L;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 20_000;
    private final Semaphore connections = new Semaphore(8);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AdminBotStatusService mapping;
    private final TokenService tokenService;
    private final String baseUrl;
    private final String token;

    public AdminBotLiveStreamService(
        AdminBotStatusService mapping,
        TokenService tokenService,
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String token
    ) {
        this.mapping = mapping;
        this.tokenService = tokenService;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    public SseEmitter subscribe(String level, Long after, Authentication authentication) {
        // Enforce the same signed/expiring ADMIN bearer at the handshake and
        // later on every upstream event, without a forced lifetime timer.
        if (authentication == null || !(authentication.getCredentials() instanceof String jwt)
            || !isAuthorizedToken(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sessão administrativa inválida");
        }
        // The /admin/** SecurityFilterChain enforces ADMIN on the handshake.
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetria interna indisponível");
        }
        if (!connections.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitos streams administrativos");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_LIFETIME_MS);
        AtomicReference<HttpURLConnection> upstream = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            HttpURLConnection connection = upstream.getAndSet(null);
            if (connection != null) connection.disconnect();
            connections.release();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            String bearer = (String) authentication.getCredentials();
            workers.execute(() -> bridge(emitter, level, after, bearer, upstream, closed, cleanup));
        } catch (RuntimeException ex) {
            cleanup.run();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stream administrativo indisponível", ex);
        }
        return emitter;
    }

    private void bridge(
        SseEmitter emitter, String level, Long after, String browserToken,
        AtomicReference<HttpURLConnection> upstream,
        AtomicBoolean closed, Runnable cleanup
    ) {
        try {
            var urlBuilder = UriComponentsBuilder.fromUriString(baseUrl + "/admin-stream");
            if (level != null && !level.isBlank()) urlBuilder.queryParam("level", level);
            if (after != null) urlBuilder.queryParam("after", after);
            URI uri = urlBuilder.build().encode().toUri();
            if (closed.get()) return;
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            upstream.set(connection);
            if (closed.get()) {
                upstream.compareAndSet(connection, null);
                connection.disconnect();
                return;
            }
            connection.setConnectTimeout(3000);
            // Coddy publishes status ~every 5s; fail promptly if the upstream stalls.
            connection.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "text/event-stream");
            if (connection.getResponseCode() != 200) {
                LOGGER.warn("Coddy admin stream unavailable: HTTP {}", connection.getResponseCode());
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8
            ))) {
                String type = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (type != null && !data.isEmpty()) {
                            if (!isAuthorizedToken(browserToken)) {
                                // JWT expired or no longer grants ADMIN: end
                                // normally; reconnect requires a fresh token.
                                break;
                            }
                            Object mapped = mapping.decodeLiveEvent(type, data.toString());
                            var outgoing = SseEmitter.event().name(type).data(mapped);
                            if (mapped instanceof AdminBotLogs logs && logs.nextSequence() != null) {
                                outgoing.id(Long.toString(logs.nextSequence()));
                            }
                            emitter.send(outgoing);
                        }
                        type = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        type = line.substring(6).trim();
                        if (!"logs".equals(type) && !"status".equals(type)) {
                            throw new IOException("Unsupported internal event type");
                        }
                    } else if (line.startsWith("data:")) {
                        if (data.length() + line.length() > 1_048_576) {
                            throw new IOException("Internal telemetry frame too large");
                        }
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    }
                }
            }
        } catch (Exception ex) {
            if (!closed.get()) {
                LOGGER.debug("Admin SSE upstream ended: {}", ex.getClass().getSimpleName());
            }
        } finally {
            // Complete the servlet's response before disconnecting the upstream.
            // A normal upstream EOF should not abort the downstream HTTP/3 stream.
            if (!closed.get()) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // Already completed by the servlet after a disconnected client.
                }
            }
            cleanup.run();
        }
    }

    private boolean isAuthorizedToken(String jwt) {
        Authentication refreshed = tokenService.authenticateAccessToken(jwt);
        return refreshed != null && refreshed.isAuthenticated()
            && refreshed.getAuthorities().stream()
                .anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()));
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
