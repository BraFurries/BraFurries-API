package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GuildManagementAccessServiceTest {
    @Mock ManagedServersDiscordIdentityService identityService;
    @Mock CoddyGuildManagementClient coddyClient;
    @InjectMocks GuildManagementAccessService service;

    @Test void authorizesThroughCoddyAndKeepsLargeGuildIdAsString() {
        String guildId = "123456789012345678";
        when(identityService.findDiscordUserId("user@example.com")).thenReturn(42L);
        when(coddyClient.getResources(guildId, 42L)).thenReturn(resources(guildId));
        var result = service.authorize(new UsernamePasswordAuthenticationToken("USER@example.com", "x"), guildId);
        assertEquals(guildId, result.resources().guildId());
    }

    @Test void rejectsUserWithoutLinkedDiscordBeforeCallingCoddy() {
        when(identityService.findDiscordUserId("user@example.com")).thenReturn(null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.authorize(new UsernamePasswordAuthenticationToken("user@example.com", "x"), "10"));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(coddyClient);
    }

    @Test void failsClosedWhenCoddyReturnsAnotherGuild() {
        when(identityService.findDiscordUserId("user@example.com")).thenReturn(42L);
        when(coddyClient.getResources("10", 42L)).thenReturn(resources("11"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.authorize(new UsernamePasswordAuthenticationToken("user@example.com", "x"), "10"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    private static GuildResources resources(String guildId) {
        return new GuildResources(guildId, List.of(), List.of(), List.of(), new BotCapabilities(true, true, true, List.of()));
    }
}
