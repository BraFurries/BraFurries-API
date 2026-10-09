package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordUserState;
import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.LinkDiscordIdentityResponse;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDiscordIdentityServiceTest {

    @Mock UserRepository users;
    @Mock UserDiscordRepository discordAccounts;
    @Mock ConfirmedIdentityClusterService clusters;
    @Mock AdminDiscordIdentityClient client;
    @Mock AdminDiscordIdentityPersistenceService persistence;

    private AdminDiscordIdentityService service;

    @BeforeEach
    void setUp() {
        service = new AdminDiscordIdentityService(
            users,
            discordAccounts,
            clusters,
            client,
            persistence
        );
    }

    @Test
    void previewsDiscordUserThatDoesNotExistInDatabase() {
        when(users.existsById(10)).thenReturn(true);
        when(client.getUser("123456789012345678")).thenReturn(
            new DiscordUserState(
                "123456789012345678",
                "outside.user",
                "Outside User",
                "https://cdn.example/avatar.png",
                false
            )
        );
        when(discordAccounts.findByDiscordUserId(123456789012345678L))
            .thenReturn(Optional.empty());
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10));

        var preview = service.preview(10, "123456789012345678");

        assertEquals("123456789012345678", preview.discordUserId());
        assertNull(preview.existingUserId());
        assertFalse(preview.alreadyInConfirmedCluster());
    }

    @Test
    void previewsExistingDiscordUserWithoutCreatingAnything() {
        User existingUser = new User();
        existingUser.setId(20);
        existingUser.setDisplayName("Existing");
        UserDiscord account = new UserDiscord();
        account.setUser(existingUser);
        account.setDiscordUserId(123456789012345678L);

        when(users.existsById(10)).thenReturn(true);
        when(client.getUser("123456789012345678")).thenReturn(
            new DiscordUserState(
                "123456789012345678",
                "existing.user",
                "Existing User",
                null,
                false
            )
        );
        when(discordAccounts.findByDiscordUserId(123456789012345678L))
            .thenReturn(Optional.of(account));
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10, 20));

        var preview = service.preview(10, "123456789012345678");

        assertEquals(20, preview.existingUserId());
        assertEquals("Existing", preview.existingUserDisplayName());
        assertTrue(preview.alreadyInConfirmedCluster());
    }

    @Test
    void confirmRefetchesDiscordBeforePersisting() {
        when(users.existsById(10)).thenReturn(true);
        var state = new DiscordUserState(
            "123456789012345678",
            "outside.user",
            "Outside User",
            null,
            false
        );
        when(client.getUser("123456789012345678")).thenReturn(state);
        var expected = new LinkDiscordIdentityResponse(20, true, null);
        when(persistence.createOrLink(
            10,
            123456789012345678L,
            state,
            "mesma pessoa",
            "admin@example.com"
        )).thenReturn(expected);

        var result = service.confirm(
            10,
            "123456789012345678",
            " mesma pessoa ",
            "admin@example.com"
        );

        assertEquals(expected, result);
        verify(client).getUser("123456789012345678");
    }
}
