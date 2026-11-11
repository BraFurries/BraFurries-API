package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyWebDtos.ChannelTarget;
import com.Brafurries.API.admin.dto.AdminCoddyWebDtos.Presence;
import com.Brafurries.API.common.BotBaseUrlNormalizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
public class AdminCoddyWebClient {
    public record RuntimeGuild(String guildId, String guildName, List<ChannelTarget> channels) {}
    public record RuntimeTargets(List<RuntimeGuild> guilds) {}
    public record Delivery(String guildId, String channelId, String messageId) {}

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    @Autowired
    public AdminCoddyWebClient(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String token,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeout,
        @Value("${app.bot.read-timeout-ms:3000}") int readTimeout
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeout, 1)));
        factory.setReadTimeout(Duration.ofMillis(Math.max(readTimeout, 1)));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    AdminCoddyWebClient(RestClient http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    private String bearer() {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Autenticacao interna do Coddy indisponivel");
        }
        return token;
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy indisponivel para operacoes administrativas");
    }

    public Presence getPresence() {
        String secret = bearer();
        try {
            Presence result = http.get().uri(baseUrl + "/admin/presence")
                .headers(h -> h.setBearerAuth(secret)).retrieve().body(Presence.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public Presence updatePresence(Presence desired) {
        String secret = bearer();
        try {
            Presence result = http.put().uri(baseUrl + "/admin/presence")
                .headers(h -> h.setBearerAuth(secret))
                .body(desired).retrieve().body(Presence.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public RuntimeTargets messageTargets() {
        String secret = bearer();
        try {
            RuntimeTargets result = http.get().uri(baseUrl + "/admin/message-targets")
                .headers(h -> h.setBearerAuth(secret)).retrieve().body(RuntimeTargets.class);
            if (result == null || result.guilds() == null) throw unavailable();
            return result;
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    public Delivery send(long guildId, long channelId, String content) {
        String secret = bearer();
        try {
            Delivery result = http.post().uri(baseUrl + "/guilds/{guildId}/admin-message", guildId)
                .headers(h -> h.setBearerAuth(secret))
                .body(Map.of("channelId", Long.toString(channelId), "content", content))
                .retrieve().body(Delivery.class);
            if (result == null || result.messageId() == null) throw unavailable();
            return result;
        } catch (HttpClientErrorException ex) {
            // An explicit Discord-side rejection means the effect did not happen.
            // Never forward raw internal URLs, tokens, or response bodies.
            if (ex.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Coddy sem permissao para enviar ao canal");
            }
            if (ex.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Servidor ou canal nao esta disponivel para o Coddy");
            }
            if (ex.getStatusCode().value() == 422) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mensagem rejeitada pelo Coddy");
            }
            throw unavailable();
        } catch (RestClientException ex) {
            // Timeouts and 5xx can be post-send; never retry here.
            throw unavailable();
        }
    }
}
