package com.Brafurries.API.admin;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.event.EventPartnershipService;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserReliabilityServiceTest {

    @Mock UserRepository users;
    @Mock UserWarningRepository warnings;
    @Mock UserBanRepository bans;
    @Mock EventRepository events;
    @Mock EventStaffRepository eventStaff;
    @Mock UserCommunityStatusRepository communities;
    @Mock UserDiscordRepository discord;
    @Mock UserTelegramRepository telegram;
    @Mock EventPartnershipService partnerships;

    @Test
    void composesTheExistingObjectiveBreakdownWithoutChangingTheFormula() {
        User user = new User();
        user.setId(12);
        user.setDisplayName("Furry");
        when(users.findById(12)).thenReturn(Optional.of(user));
        when(warnings.countWarningsByCommunity(12)).thenReturn(List.of());
        when(events.findByHostUserId(12)).thenReturn(List.of());
        when(eventStaff.findByUserId(12)).thenReturn(List.of());
        when(partnerships.findActivePartnerEventIds(List.of())).thenReturn(Set.of());
        when(discord.findByUserIdIn(List.of(12))).thenReturn(List.of());
        when(telegram.findByUserIdIn(List.of(12))).thenReturn(List.of());

        var response = service().getUserReliability(12, null);

        assertEquals(12, response.user().id());
        assertEquals(0, response.events().managedEvents());
        assertEquals(0, response.communities().memberships());
        assertEquals(100, response.reliability().score());
        assertEquals("alta", response.reliability().level());
        assertEquals(List.of("discord_nao_vinculado"), response.reliability().signals());
    }

    @Test
    void treatsMultipleLegacyExternalAccountsAsLinkedButAmbiguous() {
        User user = new User();
        user.setId(13);
        UserDiscord firstDiscord = new UserDiscord();
        UserDiscord secondDiscord = new UserDiscord();
        UserTelegram firstTelegram = new UserTelegram();
        UserTelegram secondTelegram = new UserTelegram();

        when(users.findById(13)).thenReturn(Optional.of(user));
        when(warnings.countWarningsByCommunity(13)).thenReturn(List.of());
        when(events.findByHostUserId(13)).thenReturn(List.of());
        when(eventStaff.findByUserId(13)).thenReturn(List.of());
        when(partnerships.findActivePartnerEventIds(List.of())).thenReturn(Set.of());
        when(discord.findByUserIdIn(List.of(13))).thenReturn(List.of(firstDiscord, secondDiscord));
        when(telegram.findByUserIdIn(List.of(13))).thenReturn(List.of(firstTelegram, secondTelegram));

        var account = service().getUserReliability(13, null).account();

        assertEquals(true, account.linkedDiscord());
        assertEquals(true, account.linkedTelegram());
        assertEquals(true, account.discordAmbiguous());
        assertEquals(true, account.telegramAmbiguous());
        assertEquals(null, account.discordUserId());
        assertEquals(null, account.telegramUserId());
    }

    @Test
    void returns404ForUnknownUser() {
        when(users.findById(404)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().getUserReliability(404, null)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private AdminUserReliabilityService service() {
        return new AdminUserReliabilityService(
            users, warnings, bans, events, eventStaff, communities, discord, telegram, partnerships
        );
    }
}
