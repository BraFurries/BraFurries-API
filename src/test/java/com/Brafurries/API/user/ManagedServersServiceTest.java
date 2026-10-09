package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.Brafurries.API.user.dto.UserManagedServerDtos.ManagedServerResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManagedServersServiceTest {

    @Mock
    private ManagedServersDiscordIdentityService discordIdentityService;

    @Mock
    private CoddyManagedGuildsClient coddyManagedGuildsClient;

    @InjectMocks
    private ManagedServersService service;

    @Test
    void returnsOnlyMetadataForEligibleGuildsProvidedByCoddy() {
        when(discordIdentityService.findDiscordUserId("user@example.com")).thenReturn(42L);
        when(coddyManagedGuildsClient.findManagedGuilds(42L)).thenReturn(List.of(
            new CoddyManagedGuildsClient.ManagedGuild("10", "Owner guild", 300L, "https://cdn.discordapp.com/icons/10/icon.png"),
            new CoddyManagedGuildsClient.ManagedGuild("123456789012345678", "Admin guild", null, null),
            new CoddyManagedGuildsClient.ManagedGuild(null, "invalid", 1L, null),
            new CoddyManagedGuildsClient.ManagedGuild("30", " ", 1L, null)
        ));

        List<ManagedServerResponse> result = service.getLoggedUserManagedServers(authentication());

        assertEquals(List.of(
            new ManagedServerResponse("10", "Owner guild", 300L, true, "https://cdn.discordapp.com/icons/10/icon.png"),
            new ManagedServerResponse("123456789012345678", "Admin guild", null, true, null)
        ), result);
    }

    @Test
    void returnsEmptyWithoutDiscordIdentityAndDoesNotCallCoddy() {
        when(discordIdentityService.findDiscordUserId("user@example.com")).thenReturn(null);

        assertEquals(List.of(), service.getLoggedUserManagedServers(authentication()));

        verifyNoInteractions(coddyManagedGuildsClient);
    }

    @Test
    void returnsEmptyWhenCoddyFindsNoEligibleGuild() {
        when(discordIdentityService.findDiscordUserId("user@example.com")).thenReturn(42L);
        when(coddyManagedGuildsClient.findManagedGuilds(42L)).thenReturn(List.of());

        assertEquals(List.of(), service.getLoggedUserManagedServers(authentication()));
    }

    @Test
    void preservesCoddyUnavailabilityAsAServiceUnavailableResponse() {
        when(discordIdentityService.findDiscordUserId("user@example.com")).thenReturn(42L);
        when(coddyManagedGuildsClient.findManagedGuilds(42L)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy unavailable"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.getLoggedUserManagedServers(authentication()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken("USER@example.com", "ignored");
    }

}
