package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserRoleRequest;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserStatusRequest;
import com.Brafurries.API.entity.api.ApiRole;
import com.Brafurries.API.entity.api.ApiUserRole;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.api.ApiRoleRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUsersServiceTest {

    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserTelegramRepository telegram;
    @Mock UserBanRepository bans;
    @Mock ApiRoleRepository roles;
    @Mock ApiUserRoleRepository userRoles;

    @Test
    void listsRealDiscordUsernameInsteadOfDisplayName() {
        User user = user(7, "Furry");
        UserDiscord account = discordAccount(user, 1001L, "furry.user", "Furry Display");

        when(users.searchAdminUsersFiltered(
            eq("furry"), isNull(), isNull(), isNull(), any(LocalDate.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(discord.findByUserIdIn(any())).thenReturn(List.of(account));
        when(telegram.findByUserIdIn(any())).thenReturn(List.of());
        when(bans.countActiveBansByUserIds(any(), any(LocalDate.class))).thenReturn(List.of());
        when(userRoles.findRoleNamesByUserId(7)).thenReturn(List.of());

        var response = service().listUsers(1, 20, " furry ", null, null);
        var item = response.items().getFirst();

        assertEquals("furry.user", item.discordUsername());
        assertFalse(item.discordAmbiguous());
        assertEquals("member", item.role());
    }

    @Test
    void marksMultipleLegacyDiscordLinksAsAmbiguousAndPassesFiltersBeforePagination() {
        User user = user(7, "Furry");
        UserDiscord first = discordAccount(user, 1001L, "furry.one", "Furry One");
        UserDiscord second = discordAccount(user, 1002L, "furry.two", "Furry Two");

        when(users.searchAdminUsersFiltered(
            eq("furry"), isNull(), eq("active"), eq("admin"), any(LocalDate.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(user), PageRequest.of(1, 20), 41));
        when(discord.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(telegram.findByUserIdIn(any())).thenReturn(List.of());
        when(bans.countActiveBansByUserIds(any(), any(LocalDate.class))).thenReturn(List.of());
        when(userRoles.findRoleNamesByUserId(7)).thenReturn(List.of("admin"));

        var response = service().listUsers(2, 20, " furry ", "ACTIVE", "ADMIN");
        var item = response.items().getFirst();

        assertNull(item.discordUsername());
        assertTrue(item.discordAmbiguous());
        assertEquals("admin", item.role());
        assertEquals(2, response.pagination().page());
        assertEquals(41, response.pagination().totalItems());
        assertEquals(3, response.pagination().totalPages());

        verify(users).searchAdminUsersFiltered(
            eq("furry"), isNull(), eq("active"), eq("admin"), any(LocalDate.class), any(Pageable.class)
        );
    }

    @Test
    void searchesUsernameWithOptionalAtPrefix() {
        when(users.searchAdminUsersFiltered(
            eq("dve0730"), isNull(), isNull(), isNull(), any(LocalDate.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service().listUsers(1, 20, " @dve0730 ", null, null);

        verify(users).searchAdminUsersFiltered(
            eq("dve0730"), isNull(), isNull(), isNull(), any(LocalDate.class), any(Pageable.class)
        );
    }

    @Test
    void searchesDiscordSnowflakeExactlyWithoutTextLikeFallback() {
        User user = user(7, "Furry");
        String snowflake = "123456789012345678";
        when(users.searchAdminUsersFiltered(
            isNull(), eq(123456789012345678L), isNull(), isNull(), any(LocalDate.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(discord.findByUserIdIn(any())).thenReturn(List.of());
        when(telegram.findByUserIdIn(any())).thenReturn(List.of());
        when(bans.countActiveBansByUserIds(any(), any(LocalDate.class))).thenReturn(List.of());
        when(userRoles.findRoleNamesByUserId(7)).thenReturn(List.of());

        var response = service().listUsers(1, 20, snowflake, null, null);

        assertEquals(7, response.items().getFirst().id());
        verify(users).searchAdminUsersFiltered(
            isNull(), eq(123456789012345678L), isNull(), isNull(), any(LocalDate.class), any(Pageable.class)
        );
    }

    @Test
    void rejectsMutedFilterBecauseAdminListHasNoGlobalMutedSemantics() {
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().listUsers(1, 20, null, "muted", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void updatesOnlySupportedPlatformRoles() {
        User user = new User();
        user.setId(7);
        user.setDisplayName("Furry");
        ApiRole moderator = new ApiRole();
        moderator.setId(3);
        moderator.setName("moderator");

        when(users.findById(7)).thenReturn(Optional.of(user));
        when(roles.findByNameIgnoreCase("moderator")).thenReturn(Optional.of(moderator));
        when(userRoles.findRoleNamesByUserId(7)).thenReturn(List.of("moderator"));
        when(userRoles.save(any(ApiUserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().updateRole(7, new UpdateUserRoleRequest("moderator"));

        assertEquals("moderator", response.role());
        verify(userRoles).deleteAdminScreenRolesByUserId(7, Set.of("member", "supporter", "moderator", "admin"));
        ArgumentCaptor<ApiUserRole> saved = ArgumentCaptor.forClass(ApiUserRole.class);
        verify(userRoles).save(saved.capture());
        assertSame(user, saved.getValue().getUser());
        assertSame(moderator, saved.getValue().getRole());
    }

    @Test
    void rejectsGlobalBanSemanticsWithoutModerationContext() {
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().updateStatus(7, new UpdateUserStatusRequest("banned", "sem contexto"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private User user(int id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }

    private UserDiscord discordAccount(User user, long discordUserId, String username, String displayName) {
        UserDiscord account = new UserDiscord();
        account.setUser(user);
        account.setDiscordUserId(discordUserId);
        account.setUsername(username);
        account.setDisplayName(displayName);
        return account;
    }

    private AdminUsersService service() {
        return new AdminUsersService(users, discord, telegram, bans, roles, userRoles);
    }
}
