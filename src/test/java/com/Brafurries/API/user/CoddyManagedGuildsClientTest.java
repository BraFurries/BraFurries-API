package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class CoddyManagedGuildsClientTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsCoddyManagedGuildMetadataUsingTheInternalStatusToken() {
        server.createContext("/managed-guilds/42", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {"guilds":[
                  {"guild_id":"101","name":"Owner guild","member_count":55,"icon_url":"https://cdn.discordapp.com/icons/101/icon.png"},
                  {"guild_id":"102","name":"Admin guild","member_count":null,"icon_url":null}
                ]}
                """);
        });

        List<CoddyManagedGuildsClient.ManagedGuild> guilds = client().findManagedGuilds(42L);

        assertEquals("Bearer bot-secret", authorization.get());
        assertEquals(2, guilds.size());
        assertEquals("101", guilds.getFirst().guildId());
        assertEquals("Owner guild", guilds.getFirst().name());
        assertEquals(55L, guilds.getFirst().memberCount());
        assertEquals("https://cdn.discordapp.com/icons/101/icon.png", guilds.getFirst().iconUrl());
        assertEquals("102", guilds.get(1).guildId());
        assertEquals(null, guilds.get(1).memberCount());
        assertEquals(null, guilds.get(1).iconUrl());
    }

    @Test
    void reportsServiceUnavailableWhenCoddyCannotBeReached() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> new CoddyManagedGuildsClient(RestClient.builder().build(), "http://127.0.0.1:1", "token")
                .findManagedGuilds(42L));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    private CoddyManagedGuildsClient client() {
        return new CoddyManagedGuildsClient(RestClient.builder().build(), baseUrl(), "bot-secret");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
