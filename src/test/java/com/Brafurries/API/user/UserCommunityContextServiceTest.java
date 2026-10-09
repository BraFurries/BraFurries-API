package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityStore;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserEconomy;
import com.Brafurries.API.entity.user.UserInventory;
import com.Brafurries.API.entity.user.UserLevel;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserEconomyRepository;
import com.Brafurries.API.repository.user.UserInventoryRepository;
import com.Brafurries.API.repository.user.UserLevelRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCommunityContextServiceTest {
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserLevelRepository levels;
    @Mock UserEconomyRepository economies;
    @Mock UserInventoryRepository inventory;
    @Mock UserWarningRepository warnings;
    @Mock UserBanRepository bans;

    @Test
    void refusesACommunityOutsideTheAuthenticatedUsersMemberships() {
        User user = user(7);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 4)).thenReturn(List.of());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service().getLoggedUserContext("member@example.com", 4));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("Contexto comunitário não encontrado", error.getReason());
    }

    @Test
    void keepsAmbiguousScalarsNullAndReturnsOnlyRecentInventory() {
        User user = user(7);
        Community community = community(4);
        UserCommunityStatus first = membership(1, community);
        UserCommunityStatus duplicate = membership(2, community);
        UserLevel level = new UserLevel();
        level.setCurrentLevel(8); level.setTotalXp(4200L);
        UserEconomy economy = new UserEconomy();
        economy.setBankBalance(900);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 4)).thenReturn(List.of(first, duplicate));
        when(levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of(level, new UserLevel()));
        when(economies.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of(economy));
        when(inventory.findTop6ByOwnerUserIdAndCommunityIdOrderByIdDesc(7, 4)).thenReturn(List.of(inventory(12)));
        when(warnings.countByUserIdAndCommunityId(7, 4)).thenReturn(2L);
        when(warnings.countByUserIdAndCommunityIdAndExpiredFalse(7, 4)).thenReturn(1L);
        when(bans.countByUserIdAndCommunityId(7, 4)).thenReturn(1L);
        when(bans.countActiveBansByUserIdAndCommunityId(7, 4, LocalDate.now())).thenReturn(1L);

        var response = service().getLoggedUserContext("Member@Example.com", 4);

        assertEquals(MemberDataState.AMBIGUOUS, response.membership().state());
        assertNull(response.membership().approved());
        assertEquals(MemberDataState.AMBIGUOUS, response.progression().state());
        assertNull(response.progression().totalXp());
        assertEquals(MemberDataState.AVAILABLE, response.economy().state());
        assertEquals(900, response.economy().balance());
        assertEquals(12, response.inventory().getFirst().inventoryId());
        assertEquals(2, response.moderation().totalWarnings());
    }

    @Test
    void inactiveCommunityContextFailsClosed() {
        User user = user(7);
        Community community = community(4);
        community.getDiscord().setActive(false);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 4))
            .thenReturn(List.of(membership(1, community)));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().getLoggedUserContext("member@example.com", 4)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void returnsAvailableMembershipAndProgressionWhenThereIsExactlyOneRecord() {
        User user = user(7);
        Community community = community(4);
        UserCommunityStatus membership = membership(1, community);
        membership.setApproved(true);
        membership.setApprovedAt(LocalDateTime.of(2026, 2, 1, 10, 0));
        membership.setIsPresent(true);
        membership.setIsVip(true);
        UserLevel level = new UserLevel();
        level.setCurrentLevel(8); level.setTotalXp(4200L);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 4)).thenReturn(List.of(membership));
        when(levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of(level));
        when(economies.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of());
        when(inventory.findTop6ByOwnerUserIdAndCommunityIdOrderByIdDesc(7, 4)).thenReturn(List.of());
        noModeration(7, 4);

        var response = service().getLoggedUserContext("member@example.com", 4);

        assertEquals(MemberDataState.AVAILABLE, response.membership().state());
        assertEquals(Boolean.TRUE, response.membership().approved());
        assertEquals(Boolean.TRUE, response.membership().present());
        assertEquals(Boolean.TRUE, response.membership().vip());
        assertEquals(MemberDataState.AVAILABLE, response.progression().state());
        assertEquals(8, response.progression().level());
        assertEquals(4200L, response.progression().totalXp());
        assertEquals(MemberDataState.NOT_FOUND, response.economy().state());
        assertNull(response.economy().balance());
    }

    @Test
    void doesNotInventProgressionOrEconomyWhenRowsAreMissingOrDuplicated() {
        User user = user(7);
        Community community = community(4);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 4)).thenReturn(List.of(membership(1, community)));
        when(levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of());
        when(economies.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(7, 4)).thenReturn(List.of(new UserEconomy(), new UserEconomy()));
        when(inventory.findTop6ByOwnerUserIdAndCommunityIdOrderByIdDesc(7, 4)).thenReturn(List.of());
        noModeration(7, 4);

        var response = service().getLoggedUserContext("member@example.com", 4);

        assertEquals(MemberDataState.NOT_FOUND, response.progression().state());
        assertNull(response.progression().level());
        assertNull(response.progression().totalXp());
        assertEquals(MemberDataState.AMBIGUOUS, response.economy().state());
        assertNull(response.economy().balance());
    }

    private UserCommunityContextService service() {
        return new UserCommunityContextService(users, memberships, levels, economies, inventory, warnings, bans);
    }

    private User user(int id) { User user = new User(); user.setId(id); return user; }
    private Community community(int id) {
        Community community = new Community();
        community.setId(id);
        community.setName("BraFurries");
        CommunityDiscord discord = new CommunityDiscord();
        discord.setCommunity(community);
        discord.setGuildId(1000L + id);
        discord.setActive(true);
        community.setDiscord(discord);
        return community;
    }
    private UserCommunityStatus membership(int id, Community community) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setId(id); membership.setCommunity(community); membership.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0)); return membership;
    }
    private UserInventory inventory(int id) {
        CommunityStore item = new CommunityStore(); item.setId(3); item.setItemName("Badge"); item.setIsService(false);
        UserInventory inventory = new UserInventory(); inventory.setId(id); inventory.setStoreItem(item); inventory.setQuantity(1); return inventory;
    }
    private void noModeration(int userId, int communityId) {
        when(warnings.countByUserIdAndCommunityId(userId, communityId)).thenReturn(0L);
        when(warnings.countByUserIdAndCommunityIdAndExpiredFalse(userId, communityId)).thenReturn(0L);
        when(bans.countByUserIdAndCommunityId(userId, communityId)).thenReturn(0L);
        when(bans.countActiveBansByUserIdAndCommunityId(userId, communityId, LocalDate.now())).thenReturn(0L);
    }
}
