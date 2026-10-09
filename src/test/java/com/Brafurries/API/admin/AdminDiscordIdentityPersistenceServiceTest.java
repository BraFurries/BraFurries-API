package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordUserState;
import com.Brafurries.API.admin.dto.UserIdentityDtos.IdentityLinkView;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserIdentityLinkSource;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDiscordIdentityPersistenceServiceTest {

    @Mock UserRepository users;
    @Mock UserDiscordRepository discordAccounts;
    @Mock UserIdentityLinkService identityLinks;

    private AdminDiscordIdentityPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new AdminDiscordIdentityPersistenceService(
            users,
            discordAccounts,
            identityLinks
        );
    }

    @Test
    void unknownDiscordCreatesDedicatedUserThenConfirmedLink() {
        User source = new User();
        source.setId(10);
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(source));
        when(discordAccounts.findByDiscordUserId(123456789012345678L))
            .thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(20);
            return created;
        });
        when(discordAccounts.saveAndFlush(any(UserDiscord.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        IdentityLinkView link = new IdentityLinkView(
            99L,
            null,
            null,
            UserIdentityLinkStatus.CONFIRMED,
            UserIdentityLinkSource.ADMIN,
            "mesma pessoa",
            1,
            LocalDateTime.now(),
            null,
            null,
            null
        );
        when(identityLinks.create(
            10,
            20,
            UserIdentityLinkStatus.CONFIRMED,
            "mesma pessoa",
            "admin@example.com"
        )).thenReturn(link);

        var result = service.createOrLink(
            10,
            123456789012345678L,
            new DiscordUserState(
                "123456789012345678",
                "outside.user",
                "Outside User",
                "https://cdn.example/avatar.png",
                false
            ),
            "mesma pessoa",
            "admin@example.com"
        );

        assertEquals(20, result.targetUserId());
        assertTrue(result.createdUser());

        ArgumentCaptor<UserDiscord> discordCaptor = ArgumentCaptor.forClass(UserDiscord.class);
        verify(discordAccounts).saveAndFlush(discordCaptor.capture());
        assertEquals(20, discordCaptor.getValue().getUser().getId());
        assertEquals(123456789012345678L, discordCaptor.getValue().getDiscordUserId());
        assertEquals("outside.user", discordCaptor.getValue().getUsername());
    }

    @Test
    void alreadyRegisteredDiscordLinksItsExistingUserWithoutCreatingAnother() {
        User source = new User();
        source.setId(10);
        User target = new User();
        target.setId(20);
        UserDiscord existing = new UserDiscord();
        existing.setUser(target);
        existing.setDiscordUserId(123456789012345678L);

        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(source));
        when(discordAccounts.findByDiscordUserId(123456789012345678L))
            .thenReturn(Optional.of(existing));
        when(identityLinks.create(
            10,
            20,
            UserIdentityLinkStatus.CONFIRMED,
            "mesma pessoa",
            "admin@example.com"
        )).thenReturn(null);

        var result = service.createOrLink(
            10,
            123456789012345678L,
            new DiscordUserState(
                "123456789012345678",
                "existing.user",
                "Existing User",
                null,
                false
            ),
            "mesma pessoa",
            "admin@example.com"
        );

        assertEquals(20, result.targetUserId());
        verify(users, never()).saveAndFlush(any(User.class));
        verify(discordAccounts, never()).saveAndFlush(any(UserDiscord.class));
    }
}
