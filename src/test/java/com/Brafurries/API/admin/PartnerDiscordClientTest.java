package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PartnerDiscordClientTest {
    private static final String TARGET = "http://coddy/guilds/123/partners/sync";

    private record Fixture(PartnerDiscordClient client, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new PartnerDiscordClient(builder.build(), "http://coddy", "internal-token"), server);
    }

    @Test
    void forwardsAuthorizedSyncToExactlyTheInternalCoddyEndpoint() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
            .andRespond(withSuccess("{\"threadId\":\"987\"}", MediaType.APPLICATION_JSON));
        assertEquals("987", f.client.sync("123", Map.of("partnerId", 7)).get("threadId"));
        f.server.verify();
    }

    @Test
    void preservesSafeValidationMessageInsteadOfMisreportingBadGateway() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"invalid_partner_request\","
                + "\"message\":\"A categoria já existe; selecione o ID existente\"}"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("A categoria já existe; selecione o ID existente", ex.getReason());
        f.server.verify();
    }

    @Test
    void explainsNativeDiscordPermissionsWithoutLeakingRawHttpBody() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"discord_permission_denied\",\"details\":\"private\"}"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("permissão suficiente"));
        assertFalse(ex.getReason().contains("private"));
    }

    @Test
    void distinguishesBotAuthenticationFailureFromNetworkFailure() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"unauthorized\"}"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        assertTrue(ex.getReason().contains("autenticação interna"));
    }

    @Test
    void refusesToExposeUnexpectedUpstreamHtml() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.TEXT_HTML).body("<html>private reverse proxy details</html>"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertFalse(ex.getReason().contains("private"));
    }

    @Test
    void distinguishesMissingGuildAndMissingChannel() {
        Fixture f = fixture();
        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"guild_not_found\"}"));
        ResponseStatusException first = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, first.getStatusCode());

        f.server.expect(requestTo(TARGET)).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"discord_resource_not_found\"}"));
        ResponseStatusException second = assertThrows(ResponseStatusException.class,
            () -> f.client.sync("123", Map.of("partnerId", 7)));
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        f.server.verify();
    }
}
