package com.Brafurries.API.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AdminDiscordIdentityClientTest {

    @Test
    void fetchesGuildIndependentDiscordUserWithInternalToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AdminDiscordIdentityClient client = new AdminDiscordIdentityClient(
            builder.build(),
            "http://coddy/",
            "internal-token"
        );

        server.expect(requestTo("http://coddy/users/123456789012345678"))
            .andExpect(header("Authorization", "Bearer internal-token"))
            .andRespond(withSuccess("""
                {
                  "discordUserId":"123456789012345678",
                  "username":"outside.user",
                  "displayName":"Outside User",
                  "avatarUrl":"https://cdn.example/avatar.png",
                  "bot":false
                }
                """, MediaType.APPLICATION_JSON));

        var response = client.getUser("123456789012345678");

        assertEquals("123456789012345678", response.discordUserId());
        assertEquals("outside.user", response.username());
        assertEquals("Outside User", response.displayName());
        assertEquals("https://cdn.example/avatar.png", response.avatarUrl());
        assertFalse(response.bot());
        server.verify();
    }
}
