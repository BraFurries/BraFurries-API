package com.Brafurries.API.admin;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Only the API calls Coddy; browser-issued Discord mutations are prohibited. */
@Component
public class PartnerDiscordClient {
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
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Coddy indisponível ou recusou a operação de parceria", exception);
        }
    }
}
