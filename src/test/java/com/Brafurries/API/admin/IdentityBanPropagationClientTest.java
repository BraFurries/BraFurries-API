package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.IdentityCandidate;
import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.PropagationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityBanPropagationClientTest {

    @Test
    void postsConfirmedClusterToCoddyWithInternalToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IdentityBanPropagationClient client = new IdentityBanPropagationClient(
            builder.build(),
            "http://coddy/",
            "internal-token"
        );

        server.expect(requestTo("http://coddy/guilds/555/identity-bans/propagate"))
            .andExpect(header("Authorization", "Bearer internal-token"))
            .andExpect(jsonPath("$.banId").value(77))
            .andExpect(jsonPath("$.identities[1].discordUserId").value("222"))
            .andRespond(withSuccess(
                """
                {"banId":77,"processed":2,"effects":{"APPLIED":2}}
                """,
                MediaType.APPLICATION_JSON
            ));

        var response = client.propagate(
            555L,
            new PropagationRequest(
                77,
                "ban ativo",
                List.of(
                    new IdentityCandidate(10, "111"),
                    new IdentityCandidate(20, "222")
                )
            )
        );

        assertEquals(77, response.banId());
        assertEquals(2, response.processed());
        assertEquals(2, response.effects().get("APPLIED"));
        server.verify();
    }
}
