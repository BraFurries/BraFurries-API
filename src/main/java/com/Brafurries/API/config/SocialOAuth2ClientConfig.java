package com.Brafurries.API.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
public class SocialOAuth2ClientConfig {

    @Bean
    @ConditionalOnExpression(
        "('${GOOGLE_CLIENT_ID:}' != '' && '${GOOGLE_CLIENT_SECRET:}' != '')"
            + " || ('${DISCORD_CLIENT_ID:}' != '' && '${DISCORD_CLIENT_SECRET:}' != '')"
    )
    public ClientRegistrationRepository clientRegistrationRepository(
        @Value("${GOOGLE_CLIENT_ID:}") String googleClientId,
        @Value("${GOOGLE_CLIENT_SECRET:}") String googleClientSecret,
        @Value("${DISCORD_CLIENT_ID:}") String discordClientId,
        @Value("${DISCORD_CLIENT_SECRET:}") String discordClientSecret,
        @Value("${app.base-url}") String appBaseUrl
    ) {
        String normalizedBaseUrl = normalizeBaseUrl(appBaseUrl);
        List<ClientRegistration> registrations = new ArrayList<>();

        if (hasText(googleClientId) && hasText(googleClientSecret)) {
            registrations.add(googleRegistration(googleClientId, googleClientSecret, normalizedBaseUrl));
        }

        if (hasText(discordClientId) && hasText(discordClientSecret)) {
            registrations.add(discordRegistration(discordClientId, discordClientSecret, normalizedBaseUrl));
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }

    private ClientRegistration googleRegistration(String clientId, String clientSecret, String appBaseUrl) {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .scope("openid", "email", "profile")
            .redirectUri(appBaseUrl + "/login/oauth2/code/google")
            .build();
    }

    private ClientRegistration discordRegistration(String clientId, String clientSecret, String appBaseUrl) {
        return ClientRegistration.withRegistrationId("discord")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(appBaseUrl + "/login/oauth2/code/discord")
            .scope("identify", "email")
            .authorizationUri("https://discord.com/oauth2/authorize")
            .tokenUri("https://discord.com/api/oauth2/token")
            .userInfoUri("https://discord.com/api/users/@me")
            .userNameAttributeName("id")
            .clientName("Discord")
            .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeBaseUrl(String value) {
        if (!hasText(value)) {
            return "http://localhost:8080";
        }
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
