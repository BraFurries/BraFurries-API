package com.Brafurries.API.admin;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.repository.user.UserLocaleRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserProfileServiceTest {

    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserTelegramRepository telegram;
    @Mock UserCommunityStatusRepository communities;
    @Mock UserWarningRepository warnings;
    @Mock UserBanRepository bans;
    @Mock UserBirthdayRepository birthdays;
    @Mock UserLocaleRepository locales;
    @Mock AdminUsersService adminUsers;

    @Test
    void returnsAdditiveAccountStateWithoutCredentials() throws Exception {
        User user = new User();
        user.setId(42);
        user.setDisplayName("Furry");
        user.setUsername("furry");
        user.setEmail("furry@example.com");
        user.setPasswordHash("must-never-leak");

        when(users.findById(42)).thenReturn(Optional.of(user));
        when(discord.findByUserIdIn(List.of(user.getId()))).thenReturn(List.of());
        when(telegram.findByUserIdIn(List.of(user.getId()))).thenReturn(List.of());
        when(communities.findByUser(user)).thenReturn(List.of());
        when(communities.findFirstByUserOrderByMemberSinceAscCommunityIdAsc(user)).thenReturn(Optional.empty());
        when(warnings.findAdminProfileRecords(eq(42), any(LocalDate.class), any(Pageable.class))).thenReturn(Page.empty());
        when(bans.countActiveBansByUserId(eq(42), any(LocalDate.class))).thenReturn(1L);
        when(adminUsers.resolvePrimaryRole(42)).thenReturn("moderator");

        var response = service().getProfile(42, 1, 20);

        assertEquals("banned", response.identity().status());
        assertEquals("moderator", response.identity().role());
        assertEquals(1L, response.moderation().activeBans());
        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("must-never-leak"));
        assertFalse(json.contains("token"));
        assertFalse(json.contains("secret"));
    }


    @Test
    void mapsNumericNativeBooleanColumnsFromMariaDb() {
        User user = new User();
        user.setId(493);
        user.setDisplayName("Legacy user");

        var record = mock(UserWarningRepository.AdminProfileModerationProjection.class);
        when(record.getId()).thenReturn(494);
        when(record.getRecordType()).thenReturn("ban");
        when(record.getReason()).thenReturn("reason");
        when(record.getCommunityName()).thenReturn("BraFurries");
        when(record.getOccurredAt()).thenReturn(LocalDate.of(2026, 9, 25));
        when(record.getActive()).thenReturn(1);
        when(record.getStatus()).thenReturn("active");
        when(record.getCanAppeal()).thenReturn(0);

        when(users.findById(493)).thenReturn(Optional.of(user));
        when(discord.findByUserIdIn(List.of(user.getId()))).thenReturn(List.of());
        when(telegram.findByUserIdIn(List.of(user.getId()))).thenReturn(List.of());
        when(communities.findByUser(user)).thenReturn(List.of());
        when(communities.findFirstByUserOrderByMemberSinceAscCommunityIdAsc(user)).thenReturn(Optional.empty());
        when(warnings.findAdminProfileRecords(eq(493), any(LocalDate.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));
        when(bans.countActiveBansByUserId(eq(493), any(LocalDate.class))).thenReturn(0L);
        when(adminUsers.resolvePrimaryRole(493)).thenReturn("member");

        var response = service().getProfile(493, 1, 20);
        var moderation = response.moderation().records().getFirst();

        assertEquals(true, moderation.active());
        assertEquals(false, moderation.canAppeal());
    }

    @Test
    void preservesAmbiguousLegacyExternalAccountsWithoutChoosingOne() {
        User user = new User();
        user.setId(77);
        user.setDisplayName("Legacy");

        UserDiscord firstDiscord = new UserDiscord();
        firstDiscord.setUsername("first");
        UserDiscord secondDiscord = new UserDiscord();
        secondDiscord.setUsername("second");
        UserTelegram firstTelegram = new UserTelegram();
        firstTelegram.setUsername("first-tg");
        UserTelegram secondTelegram = new UserTelegram();
        secondTelegram.setUsername("second-tg");

        when(users.findById(77)).thenReturn(Optional.of(user));
        when(discord.findByUserIdIn(List.of(77))).thenReturn(List.of(firstDiscord, secondDiscord));
        when(telegram.findByUserIdIn(List.of(77))).thenReturn(List.of(firstTelegram, secondTelegram));
        when(communities.findByUser(user)).thenReturn(List.of());
        when(communities.findFirstByUserOrderByMemberSinceAscCommunityIdAsc(user)).thenReturn(Optional.empty());
        when(warnings.findAdminProfileRecords(eq(77), any(LocalDate.class), any(Pageable.class))).thenReturn(Page.empty());
        when(bans.countActiveBansByUserId(eq(77), any(LocalDate.class))).thenReturn(0L);
        when(adminUsers.resolvePrimaryRole(77)).thenReturn("member");

        var identity = service().getProfile(77, 1, 20).identity();

        assertEquals(null, identity.discordUsername());
        assertEquals(null, identity.telegramUsername());
        assertEquals(true, identity.discordAmbiguous());
        assertEquals(true, identity.telegramAmbiguous());
    }

    @Test
    void returns404ForUnknownUser() {
        when(users.findById(404)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().getProfile(404, 1, 20)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private AdminUserProfileService service() {
        return new AdminUserProfileService(
            users, discord, telegram, communities, warnings, bans, birthdays, locales, adminUsers
        );
    }
}

