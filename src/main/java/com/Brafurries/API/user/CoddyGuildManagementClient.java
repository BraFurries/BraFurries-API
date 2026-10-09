package com.Brafurries.API.user;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.dto.GuildManagementDtos.StructurePreviewRequest;
import com.Brafurries.API.user.dto.GuildManagementDtos.AutoJoinRolesConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CoddyGuildManagementClient {
    private final RestClient restClient;
    private final RestClient operationRestClient;
    private final String baseUrl;
    private final String statusToken;
    private final int readTimeoutMs;
    private final int operationTimeoutMs;

    @Autowired
    public CoddyGuildManagementClient(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String statusToken,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${app.bot.read-timeout-ms:3000}") int readTimeoutMs,
        @Value("${app.bot.operation-timeout-ms:30000}") int operationTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        SimpleClientHttpRequestFactory operationFactory = new SimpleClientHttpRequestFactory(); operationFactory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1))); operationFactory.setReadTimeout(Duration.ofMillis(Math.max(operationTimeoutMs, 1)));
        this.operationRestClient = RestClient.builder().requestFactory(operationFactory).build();
        this.readTimeoutMs = Math.max(readTimeoutMs, 1); this.operationTimeoutMs = Math.max(operationTimeoutMs, 1);
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.statusToken = statusToken;
    }

    CoddyGuildManagementClient(RestClient restClient, String baseUrl, String statusToken) {
        this.restClient = restClient;
        this.operationRestClient = restClient;
        this.readTimeoutMs = -1; this.operationTimeoutMs = -1;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.statusToken = statusToken;
    }
    int readTimeoutMs() { return readTimeoutMs; }
    int operationTimeoutMs() { return operationTimeoutMs; }

    public GuildResources getResources(String guildId, Long discordUserId) {
        try {
            GuildResources response = restClient.get()
                .uri(baseUrl + "/guilds/{guildId}/resources/{discordUserId}", guildId, discordUserId)
                .headers(headers -> authorize(headers))
                .retrieve().body(GuildResources.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado no Coddy");
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public Long getGuildOwner(String guildId) {
        try {
            JsonNode response = restClient.get()
                .uri(baseUrl + "/guilds/{guildId}/owner", guildId)
                .headers(this::authorize)
                .retrieve()
                .body(JsonNode.class);
            if (response == null || !response.path("ownerId").isTextual()) {
                throw unavailable();
            }
            return Long.parseLong(response.path("ownerId").asText());
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado no Coddy");
        } catch (NumberFormatException | RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode preview(String guildId, Long discordUserId, StructurePreviewRequest request) {
        try {
            JsonNode response = restClient.post()
                .uri(baseUrl + "/guilds/{guildId}/structure-preview/{discordUserId}", guildId, discordUserId)
                .headers(headers -> authorize(headers))
                .body(request).retrieve().body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado no Coddy");
        } catch (HttpClientErrorException.BadRequest ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Preview inválido");
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode xpSimulation(String guildId, Long discordUserId, Object request) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(baseUrl + "/guilds/{guildId}/xp/simulation/{discordUserId}", guildId, discordUserId)
                .headers(this::authorize)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado no Coddy");
        } catch (HttpClientErrorException.BadRequest ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Configuração de XP inválida");
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode refreshXpRuntime(String guildId, Long discordUserId) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(baseUrl + "/guilds/{guildId}/xp/runtime-refresh/{discordUserId}", guildId, discordUserId)
                .headers(this::authorize)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado no Coddy");
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode dispatchBackupSnapshot(
        String guildId,
        Long discordUserId,
        long operationId
    ) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(
                    baseUrl + "/guilds/{guildId}/backups/{discordUserId}",
                    guildId,
                    discordUserId
                )
                .headers(this::authorize)
                .body(java.util.Map.of("operationId", operationId))
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A operação de Backup não corresponde ao estado persistido"
            );
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Sem permissão para gerenciar Backups neste servidor"
            );
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Operação de Backup não encontrada nesta guild"
            );
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Operação de Backup inválida"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode backupRestorePreview(
        String guildId,
        Long discordUserId,
        int backupId,
        String scope
    ) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(
                    baseUrl
                        + "/guilds/{guildId}/backups/{backupId}/restore-preview/{discordUserId}",
                    guildId,
                    backupId,
                    discordUserId
                )
                .headers(this::authorize)
                .body(java.util.Map.of("scope", scope))
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Apenas o dono do servidor pode restaurar Backups"
            );
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Backup não encontrado nesta guild"
            );
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Preflight de restore inválido"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode dispatchBackupRestore(
        String guildId,
        Long discordUserId,
        int backupId,
        long operationId,
        String scope
    ) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(
                    baseUrl + "/guilds/{guildId}/backups/{backupId}/restore/{discordUserId}",
                    guildId,
                    backupId,
                    discordUserId
                )
                .headers(this::authorize)
                .body(java.util.Map.of(
                    "operationId", operationId,
                    "scope", scope
                ))
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A operação de restore não corresponde ao estado persistido"
            );
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Apenas o dono do servidor pode restaurar Backups"
            );
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Backup ou operação de restore não encontrados nesta guild"
            );
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Operação de restore inválida"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode themePreview(
        String guildId,
        Long discordUserId,
        Object definition
    ) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(
                    baseUrl + "/guilds/{guildId}/themes/preview/{discordUserId}",
                    guildId,
                    discordUserId
                )
                .headers(this::authorize)
                .body(definition)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Sem permissão para gerenciar Themes neste servidor"
            );
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Servidor ou recurso do Theme não encontrado"
            );
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Já existe uma aplicação de Theme em andamento"
            );
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Theme incompatível com o estado atual do servidor"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode dispatchThemeApply(
        String guildId,
        Long discordUserId,
        long applicationId,
        long operationId
    ) {
        return dispatchThemeOperation(
            guildId,
            discordUserId,
            applicationId,
            operationId,
            "apply"
        );
    }

    public JsonNode dispatchThemeRestore(
        String guildId,
        Long discordUserId,
        long applicationId,
        long operationId
    ) {
        return dispatchThemeOperation(
            guildId,
            discordUserId,
            applicationId,
            operationId,
            "restore"
        );
    }

    private JsonNode dispatchThemeOperation(
        String guildId,
        Long discordUserId,
        long applicationId,
        long operationId,
        String action
    ) {
        try {
            JsonNode response = operationRestClient.post()
                .uri(
                    baseUrl
                        + "/guilds/{guildId}/themes/applications/{applicationId}/{action}/{discordUserId}",
                    guildId,
                    applicationId,
                    action,
                    discordUserId
                )
                .headers(this::authorize)
                .body(java.util.Map.of("operationId", operationId))
                .retrieve()
                .body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A operação de Theme conflita com o estado atual da guild"
            );
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Sem permissão para gerenciar Themes neste servidor"
            );
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Aplicação ou operação de Theme não encontrada nesta guild"
            );
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Operação de Theme inválida"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public JsonNode reconcileVip(String guildId, Long discordUserId) {
        return operation(guildId, discordUserId, java.util.Map.of("operation", "vip-reconcile"));
    }

    public JsonNode operation(String guildId, Long discordUserId, Object request) {
        try {
            JsonNode response = operationRestClient.post().uri(baseUrl + "/guilds/{guildId}/operations/{discordUserId}", guildId, discordUserId)
                .headers(this::authorize).body(request).retrieve().body(JsonNode.class);
            if (response == null) throw unavailable();
            return response;
        } catch (HttpClientErrorException.Conflict ex) {
            String body = ex.getResponseBodyAsString();
            if (body != null && body.contains("backup_operation_busy")) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe outra operação de Backup em andamento neste servidor"
                );
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Há recursos com nomes ambíguos; revise o servidor antes de tentar novamente");
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor ou recurso não encontrado no Coddy");
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Operação inválida para o estado atual do servidor");
        } catch (RestClientException ex) { throw unavailable(); }
    }

    public AutoJoinRolesConfig autoJoin(String guildId, Long userId) { return autoJoinRequest(restClient.get().uri(baseUrl + "/guilds/{guildId}/auto-join-roles/{userId}", guildId, userId).headers(this::authorize)); }
    public AutoJoinRolesConfig addAutoJoin(String guildId, Long userId, String roleId) { return autoJoinRequest(operationRestClient.post().uri(baseUrl + "/guilds/{guildId}/auto-join-roles/{roleId}/{userId}", guildId, roleId, userId).headers(this::authorize)); }
    public AutoJoinRolesConfig removeAutoJoin(String guildId, Long userId, String roleId) { return autoJoinRequest(operationRestClient.delete().uri(baseUrl + "/guilds/{guildId}/auto-join-roles/{roleId}/{userId}", guildId, roleId, userId).headers(this::authorize)); }
    public AutoJoinRolesConfig updateAutoJoinEnabled(String guildId, Long userId, boolean enabled) { return autoJoinRequest(operationRestClient.post().uri(baseUrl + "/guilds/{guildId}/auto-join-roles/{userId}/enabled", guildId, userId).headers(this::authorize).body(java.util.Map.of("enabled", enabled))); }
    private AutoJoinRolesConfig autoJoinRequest(RestClient.RequestHeadersSpec<?> request) {
        try { AutoJoinRolesConfig value = request.retrieve().body(AutoJoinRolesConfig.class); if (value == null) throw unavailable(); return value; }
        catch (HttpClientErrorException.Forbidden ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para gerenciar este servidor"); }
        catch (HttpClientErrorException.NotFound ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor ou cargo não encontrado no Coddy"); }
        catch (HttpClientErrorException.UnprocessableEntity ex) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cargo inválido para atribuição automática"); }
        catch (RestClientException ex) { throw unavailable(); }
    }

    private void authorize(org.springframework.http.HttpHeaders headers) {
        if (statusToken != null && !statusToken.isBlank()) headers.setBearerAuth(statusToken);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy indisponível no momento");
    }
}
