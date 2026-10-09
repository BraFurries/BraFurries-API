package com.Brafurries.API.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AdminDiscordMemberClientTest {

    @Test
    void sendsInternalBearerTokenAndMapsMinimalMemberState() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AdminDiscordMemberClient client = new AdminDiscordMemberClient(builder.build(), "http://coddy/", "internal-token");
        server.expect(requestTo("http://coddy/guilds/99/members/42"))
            .andExpect(header("Authorization", "Bearer internal-token"))
            .andRespond(withSuccess("""
                {"guildId":"99","discordUserId":"42","username":"fox","displayName":"Fox","roles":[]}
                """, MediaType.APPLICATION_JSON));

        var response = client.getMember(99L, 42L);

        assertEquals("42", response.discordUserId());
        assertEquals("Fox", response.displayName());
        server.verify();
    }
}
