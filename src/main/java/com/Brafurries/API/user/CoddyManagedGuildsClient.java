package com.Brafurries.API.user;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CoddyManagedGuildsClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String statusToken;

    @Autowired
    public CoddyManagedGuildsClient(
        @Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl,
        @Value("${app.bot.status-token:}") String statusToken,
        @Value("${app.bot.connect-timeout-ms:2000}") int connectTimeoutMs,
        @Value("${app.bot.read-timeout-ms:3000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.statusToken = statusToken;
    }

    // Visible for isolated HTTP-contract tests.
    CoddyManagedGuildsClient(RestClient restClient, String baseUrl, String statusToken) {
        this.restClient = restClient;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.statusToken = statusToken;
    }

    public List<ManagedGuild> findManagedGuilds(Long discordUserId) {
        if (discordUserId == null || discordUserId <= 0) {
            return List.of();
        }

        try {
            ManagedGuildsResponse response = restClient.get()
                .uri(baseUrl + "/managed-guilds/{discordUserId}", discordUserId)
                .headers(headers -> {
                    if (statusToken != null && !statusToken.isBlank()) {
                        headers.setBearerAuth(statusToken);
                    }
                })
                .retrieve()
                .body(ManagedGuildsResponse.class);
            if (response == null || response.guilds() == null) {
                throw unavailable();
            }
            return response.guilds();
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Não foi possível verificar as permissões do Discord no momento");
    }

    public record ManagedGuild(
        @JsonAlias({"guild_id", "guildId"}) String guildId,
        String name,
        @JsonAlias({"member_count", "memberCount"}) Long memberCount,
        @JsonAlias({"icon_url", "iconUrl"}) String iconUrl
    ) {
    }

    private record ManagedGuildsResponse(List<ManagedGuild> guilds) {
    }
}
