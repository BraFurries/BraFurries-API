package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.Brafurries.API.user.dto.GuildManagementDtos.StructurePreviewRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;

class CoddyGuildManagementClientTest {
    @Test void keepsReadAndOperationTimeoutsSeparate() {
        CoddyGuildManagementClient client = new CoddyGuildManagementClient("http://coddy", "token", 2000, 3000, 30000);
        assertEquals(3000, client.readTimeoutMs());
        assertEquals(30000, client.operationTimeoutMs());
    }
    @Test void mapsInvalidPreviewFromCoddyToUnprocessableEntity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(builder.build(), "http://coddy", "token");
        server.expect(requestTo("http://coddy/guilds/123/structure-preview/42"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        StructurePreviewRequest request = new StructurePreviewRequest(
            "portaria", "Visitante", "Portaria", List.of("entrada"), List.of(), List.of(), false);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> client.preview("123", 42L, request));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        server.verify();
    }

    @Test void xpSimulationUsesAuthorizedInternalRuntimeEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(builder.build(), "http://coddy", "secret");

        server.expect(requestTo("http://coddy/guilds/123/xp/simulation/42"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
            .andRespond(withSuccess(
                "{\"guildId\":\"123\",\"points\":[],\"curve\":{\"phase1K\":\"45\",\"phase1P\":\"2\",\"phase1B\":\"0\"}}",
                MediaType.APPLICATION_JSON
            ));

        assertEquals(
            "123",
            client.xpSimulation("123", 42L, java.util.Map.of("config", java.util.Map.of()))
                .path("guildId").asText()
        );
        server.verify();
    }

    @Test void mapsInvalidXpSimulationToUnprocessableEntity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(builder.build(), "http://coddy", "token");

        server.expect(requestTo("http://coddy/guilds/123/xp/simulation/42"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> client.xpSimulation("123", 42L, java.util.Map.of("config", java.util.Map.of()))
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        server.verify();
    }

    @Test
    void backupSnapshotDispatchUsesDedicatedAuthorizedRuntimeEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(
            builder.build(), "http://coddy", "secret"
        );

        server.expect(requestTo("http://coddy/guilds/123/backups/42"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
            .andRespond(withSuccess(
                "{\"operationId\":70,\"accepted\":true}",
                MediaType.APPLICATION_JSON
            ));

        assertTrue(
            client.dispatchBackupSnapshot("123", 42L, 70L)
                .path("accepted").asBoolean()
        );
        server.verify();
    }

    @Test
    void backupRestorePreviewUsesOwnerRuntimeEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(
            builder.build(), "http://coddy", "secret"
        );

        server.expect(requestTo(
            "http://coddy/guilds/123/backups/9/restore-preview/42"
        ))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
            .andRespond(withSuccess(
                "{\"guildId\":\"123\",\"backupId\":9,\"scope\":\"full\",\"blockers\":[]}",
                MediaType.APPLICATION_JSON
            ));

        assertEquals(
            "full",
            client.backupRestorePreview("123", 42L, 9, "full")
                .path("scope").asText()
        );
        server.verify();
    }

    @Test
    void backupRestoreDispatchMapsOwnerDenialToForbidden() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CoddyGuildManagementClient client = new CoddyGuildManagementClient(
            builder.build(), "http://coddy", "secret"
        );

        server.expect(requestTo(
            "http://coddy/guilds/123/backups/9/restore/42"
        )).andRespond(withStatus(HttpStatus.FORBIDDEN));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> client.dispatchBackupRestore("123", 42L, 9, 70L, "full")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        server.verify();
    }

}
