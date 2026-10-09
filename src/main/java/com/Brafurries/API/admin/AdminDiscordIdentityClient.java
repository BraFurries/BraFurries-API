package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordUserState;
import com.Brafurries.API.common.BotBaseUrlNormalizer;
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
public class AdminDiscordIdentityClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    @Autowired
    public AdminDiscordIdentityClient(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String token,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${app.bot.read-timeout-ms:3000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)));
        factory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 1)));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    AdminDiscordIdentityClient(RestClient restClient, String baseUrl, String token) {
        this.restClient = restClient;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    public DiscordUserState getUser(String discordUserId) {
        try {
            DiscordUserState response = restClient.get()
                .uri(baseUrl + "/users/{discordUserId}", discordUserId)
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .body(DiscordUserState.class);
            if (response == null) {
                throw unavailable();
            }
            return response;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário Discord não encontrado");
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Falha de autenticação interna com o Coddy"
            );
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Consulta de usuário Discord indisponível"
        );
    }
}
