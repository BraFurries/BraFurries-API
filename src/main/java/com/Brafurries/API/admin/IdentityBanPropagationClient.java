package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.PropagationRequest;
import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.PropagationResponse;
import com.Brafurries.API.common.BotBaseUrlNormalizer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class IdentityBanPropagationClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    @Autowired
    public IdentityBanPropagationClient(
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

    IdentityBanPropagationClient(RestClient restClient, String baseUrl, String token) {
        this.restClient = restClient;
        this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl);
        this.token = token;
    }

    public PropagationResponse propagate(Long guildId, PropagationRequest request) {
        return restClient.post()
            .uri(baseUrl + "/guilds/{guildId}/identity-bans/propagate", guildId)
            .headers(headers -> {
                if (token != null && !token.isBlank()) {
                    headers.setBearerAuth(token);
                }
            })
            .body(request)
            .retrieve()
            .body(PropagationResponse.class);
    }
}
