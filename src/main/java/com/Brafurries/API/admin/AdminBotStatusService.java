package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogEntry;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogs;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotStatus;
import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AdminBotStatusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBotStatusService.class);
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient restClient;
    private final String baseUrl;
    private final String statusToken;

    @Autowired
    public AdminBotStatusService(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String statusToken,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${app.bot.read-timeout-ms:3000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.statusToken = statusToken;
    }

    // Visible for isolated HTTP-contract tests.
    AdminBotStatusService(RestClient restClient, String baseUrl, String statusToken) {
        this.restClient = restClient;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.statusToken = statusToken;
    }

    public AdminBotStatus getStatus() {
        try {
            BotStatusResponse response = authorizedGet(statusUrl()).retrieve().body(BotStatusResponse.class);
            if (response == null) {
                return offlineStatus();
            }
            return mapStatus(response);
        } catch (RestClientException ex) {
            return offlineStatus();
        }
    }

    public AdminBotLogs getLogs(Integer limit, String level, Long after, Long before) {
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/logs")
                .queryParamIfPresent("limit", java.util.Optional.ofNullable(limit))
                .queryParamIfPresent("level", java.util.Optional.ofNullable(level).filter(value -> !value.isBlank()))
                .queryParamIfPresent("after", java.util.Optional.ofNullable(after))
                .queryParamIfPresent("before", java.util.Optional.ofNullable(before))
                .toUriString();
            BotLogsResponse response = authorizedGet(url).retrieve().body(BotLogsResponse.class);
            if (response == null) {
                return unavailableLogs(limit);
            }
            return mapLogsResponse(response, limit);
        } catch (RestClientException ex) {
            logProxyFailure(ex);
            return unavailableLogs(limit);
        }
    }

    Object decodeLiveEvent(String type, String json) throws JsonProcessingException {
        if ("status".equals(type)) {
            return mapStatus(STREAM_MAPPER.readValue(json, BotStatusResponse.class));
        }
        if ("logs".equals(type)) {
            return mapLogsResponse(STREAM_MAPPER.readValue(json, BotLogsResponse.class), 1000);
        }
        throw new IllegalArgumentException("Tipo de evento administrativo inválido");
    }

    private static AdminBotStatus mapStatus(BotStatusResponse response) {
        return new AdminBotStatus(
            resolveStatus(response.ok(), response.state(), response.ready(), response.connected()),
            response.ready(), response.connected(), response.uptimeSeconds(), response.startedAt(), response.lastReadyAt(),
            firstNonNull(response.memoryUsedMb(), response.memory() == null ? null : response.memory().usedMb()),
            firstNonNull(response.memoryLimitMb(), response.memory() == null ? null : response.memory().limitMb()),
            response.cpuUsagePercent(), response.pingMs(), response.guilds(), response.users(), response.commandsLoaded(),
            response.cogsLoaded(), response.pythonVersion(), response.discordPyVersion(), response.processId()
        );
    }

    private static AdminBotLogs mapLogsResponse(BotLogsResponse response, Integer limit) {
        JsonNode rawLogs = response.items() != null ? response.items() : response.logs();
        List<AdminBotLogEntry> entries = mapLogs(rawLogs);
        return new AdminBotLogs(
            "available", entries,
            response.totalBuffered() == null ? entries.size() : response.totalBuffered(),
            response.limit() == null ? limit == null ? 200 : limit : response.limit(),
            response.oldestSequence(), response.latestSequence(), response.nextSequence(),
            Boolean.TRUE.equals(response.cursorExpired())
        );
    }

    /** Backward-compatible overload for callers that do not use incremental polling. */
    public AdminBotLogs getLogs(Integer limit, String level) {
        return getLogs(limit, level, null, null);
    }

    /** Backward-compatible overload for callers using only the incremental cursor. */
    public AdminBotLogs getLogs(Integer limit, String level, Long after) {
        return getLogs(limit, level, after, null);
    }

    private RestClient.RequestHeadersSpec<?> authorizedGet(String url) {
        return restClient.get().uri(url).headers(headers -> {
            if (statusToken != null && !statusToken.isBlank()) {
                headers.setBearerAuth(statusToken);
            }
        });
    }

    private AdminBotStatus offlineStatus() {
        return new AdminBotStatus("offline", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private String statusUrl() {
        return baseUrl + "/status";
    }

    static String normalizeBaseUrl(String configuredBaseUrl) {
        return BotBaseUrlNormalizer.normalize(configuredBaseUrl);
    }

    private static String resolveStatus(Boolean ok, String state, Boolean ready, Boolean connected) {
        String normalizedState = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        if ("restarting".equals(normalizedState)) {
            return "restarting";
        }
        if (!Boolean.FALSE.equals(ok) && Boolean.TRUE.equals(ready) && Boolean.TRUE.equals(connected)
            && (normalizedState.isEmpty() || normalizedState.equals("online") || normalizedState.equals("ready") || normalizedState.equals("running"))) {
            return "online";
        }
        return "degraded";
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static List<AdminBotLogEntry> mapLogs(JsonNode logs) {
        if (logs == null || !logs.isArray()) {
            return List.of();
        }
        List<AdminBotLogEntry> entries = new ArrayList<>();
        for (JsonNode entry : logs) {
            if (entry.isTextual()) {
                entries.add(new AdminBotLogEntry(null, null, null, null, entry.asText()));
            } else if (entry.isObject()) {
                entries.add(new AdminBotLogEntry(
                    number(entry, "sequence"),
                    text(entry, "timestamp", "time", "created_at"),
                    text(entry, "level"),
                    text(entry, "logger", "logger_name"),
                    text(entry, "message", "msg")
                ));
            }
        }
        return entries;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private void logProxyFailure(RestClientException ex) {
        if (ex instanceof HttpStatusCodeException statusException) {
            LOGGER.warn("Coddy logs proxy unavailable: httpStatus={} exception={}",
                statusException.getStatusCode().value(), ex.getClass().getSimpleName());
        } else {
            LOGGER.warn("Coddy logs proxy unavailable: exception={}", ex.getClass().getSimpleName());
        }
    }

    private static Long number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isNumber() ? value.longValue() : null;
    }

    private static AdminBotLogs unavailableLogs(Integer requestedLimit) {
        return new AdminBotLogs("unavailable", List.of(), 0,
            requestedLimit == null ? 200 : requestedLimit, null, null, null, false);
    }

    private record BotStatusResponse(
        Boolean ok,
        String state,
        Boolean ready,
        Boolean connected,
        @JsonAlias({"uptime_seconds", "uptimeSeconds"}) Long uptimeSeconds,
        @JsonAlias({"started_at", "startedAt"}) String startedAt,
        @JsonAlias({"last_ready_at", "lastReadyAt"}) String lastReadyAt,
        @JsonAlias({"memory_used_mb", "memoryUsedMb"}) Double memoryUsedMb,
        @JsonAlias({"memory_limit_mb", "memoryLimitMb"}) Double memoryLimitMb,
        @JsonAlias({"cpu_usage_percent", "cpuUsagePercent"}) Double cpuUsagePercent,
        @JsonAlias({"ping_ms", "latency_ms", "pingMs", "latencyMs"}) Double pingMs,
        Long guilds,
        Long users,
        @JsonAlias({"commands_loaded", "commandsLoaded"}) Long commandsLoaded,
        @JsonAlias({"cogs_loaded", "cogsLoaded"}) Long cogsLoaded,
        @JsonAlias({"python_version", "pythonVersion"}) String pythonVersion,
        @JsonAlias({"discord_py_version", "discordPyVersion", "discordpy_version"}) String discordPyVersion,
        @JsonAlias({"process_id", "processId", "pid"}) Long processId,
        BotMemoryResponse memory
    ) {
    }

    private record BotMemoryResponse(
        @JsonAlias({"used_mb", "usedMb"}) Double usedMb,
        @JsonAlias({"limit_mb", "limitMb"}) Double limitMb
    ) {
    }

    private record BotLogsResponse(
        Boolean ok,
        JsonNode items,
        JsonNode logs,
        Integer totalBuffered,
        Integer limit,
        @JsonAlias({"oldest_sequence", "oldestSequence"}) Long oldestSequence,
        @JsonAlias({"latest_sequence", "latestSequence"}) Long latestSequence,
        @JsonAlias({"next_sequence", "nextSequence"}) Long nextSequence,
        @JsonAlias({"cursor_expired", "cursorExpired"}) Boolean cursorExpired
    ) {
    }
}
