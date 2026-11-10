package com.Brafurries.API.admin;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Only the API calls Coddy; browser-issued Discord mutations are prohibited. */
@Component
public class PartnerDiscordClient {
    private static final Logger LOG = LoggerFactory.getLogger(PartnerDiscordClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    public PartnerDiscordClient(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String token,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${app.bot.operation-timeout-ms:30000}") int operationTimeoutMs
    ) {
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token == null ? "" : token.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(100, connectTimeoutMs)));
        factory.setReadTimeout(Duration.ofMillis(Math.max(100, operationTimeoutMs)));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // Visible for isolated HTTP-contract tests, no shared DB or Discord needed.
    PartnerDiscordClient(RestClient restClient, String baseUrl, String token) {
        this.restClient = restClient;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token == null ? "" : token.trim();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sync(String guildId, Map<String, Object> payload) {
        return call(guildId, "sync", payload);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(String guildId, Map<String, Object> payload) {
        return call(guildId, "publish", payload);
    }

    private Map<String, Object> call(String guildId, String action, Map<String, Object> payload) {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy sem autenticação interna configurada");
        }
        if (!guildId.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Guild Discord inválida");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri(baseUrl + "/guilds/{guildId}/partners/{action}", guildId, action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(payload)
                .retrieve()
                .body(Map.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Coddy retornou resposta vazia");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw mapCoddyFailure(action, exception);
        } catch (RestClientException exception) {
            LOG.warn("Partner Discord upstream connection failure: action={} exception={}",
                action, exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível conectar ao Coddy. Verifique a conexão interna API/Coddy.", exception);
        }
    }

    private ResponseStatusException mapCoddyFailure(String action, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String code = "unknown";
        String safeDetail = null;
        try {
            JsonNode parsed = MAPPER.readTree(exception.getResponseBodyAsString());
            if (parsed != null && parsed.isObject()) {
                String rawCode = parsed.path("error").asText("");
                if (rawCode.matches("[a-z_]{1,64}")) code = rawCode;
                if ("invalid_partner_request".equals(code)) {
                    String candidate = parsed.path("message").asText("");
                    if (!candidate.isBlank() && candidate.length() <= 240
                        && candidate.codePoints().noneMatch(Character::isISOControl)) {
                        safeDetail = candidate;
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Never expose arbitrary HTML or reverse-proxy error bodies.
        }
        LOG.warn("Partner Discord upstream rejected operation: action={} status={} code={}",
            action, status, code);
        if (status == 422 && "invalid_partner_request".equals(code)) {
            return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                safeDetail == null ? "Coddy rejeitou a configuração de parceria" : safeDetail);
        }
        if (status == 403 && "discord_permission_denied".equals(code)) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                "Coddy não tem permissão suficiente no canal ou categoria do Discord");
        }
        if (status == 404 && "guild_not_found".equals(code)) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "O Coddy não reconhece o servidor Discord oficial");
        }
        if (status == 404 && "discord_resource_not_found".equals(code)) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                "Canal ou thread Discord não encontrado. Revise a configuração da vitrine");
        }
        if (status == 401) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Falha na autenticação interna entre API e Coddy");
        }
        if (status == 503 && "bot_not_ready".equals(code)) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Coddy está inicializando; tente sincronizar quando estiver pronto");
        }
        if (status == 503 && "partner_auth_not_configured".equals(code)) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "A autenticação interna de parceiros não foi configurada no Coddy");
        }
        if (status == 503 && "discord_partner_operation_failed".equals(code)) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Discord recusou uma operação do Coddy; consulte o log do bot para o código de erro");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Falha na operação do Coddy (HTTP " + status + ", código " + code + ")");
    }
}
